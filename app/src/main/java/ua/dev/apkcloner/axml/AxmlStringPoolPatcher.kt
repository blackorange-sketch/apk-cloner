package ua.dev.apkcloner.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Patches package-name-derived strings directly inside a *compiled* (binary) AndroidManifest.xml,
 * without decompiling it back to text (no apktool / aapt needed on-device).
 *
 * How it works
 * ------------
 * A compiled Android binary XML file (AXML) stores every string used anywhere in the document
 * (element names, attribute names, attribute values, namespace URIs...) once, in a single global
 * "String Pool" chunk near the start of the file. Every element/attribute then just references a
 * string by its index into that pool. That means we do NOT need to walk the XML tree at all:
 * we only need to rewrite the string pool itself.
 *
 * We find every string that is exactly the old package name, or starts with
 * "<oldPackage>." / "<oldPackage>$" (covers fully-qualified activity/service/provider class
 * names, permission strings like "<pkg>.permission.C2D_MESSAGE", and content provider
 * authorities like "<pkg>.provider"), and rewrite that prefix to the new package name.
 *
 * Binary format reference: frameworks/base ResourceTypes.h/.cpp (ResStringPool_header,
 * ResChunk_header). AndroidManifest.xml compiled by aapt2 has styleCount == 0, so we don't
 * need to preserve style span data — we just carry the (empty) style section through unchanged
 * in length (0) after the rebuild.
 */
object AxmlStringPoolPatcher {

    private const val CHUNK_STRING_POOL = 0x0001
    private const val UTF8_FLAG = 1 shl 8
    private const val SORTED_FLAG = 1 shl 0

    /**
     * @param manifestBytes raw bytes of the compiled AndroidManifest.xml entry from the APK
     * @param oldPackage    original applicationId, e.g. "com.example.app"
     * @param newPackage    desired applicationId, e.g. "com.example.app.clone1"
     * @return patched manifest bytes, or the original bytes unchanged if no matching string was found
     */
    fun patchPackageName(manifestBytes: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        val buf = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Top-level chunk: ResXMLTree_header (type 0x0003), 8-byte ResChunk_header
        val topType = buf.getShort(0).toInt() and 0xFFFF
        require(topType == 0x0003) { "Not a compiled binary XML (unexpected root chunk type $topType)" }
        val topHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        val topChunkSize = buf.getInt(4)

        // The string pool chunk always immediately follows the top-level header.
        val poolStart = topHeaderSize
        val poolType = buf.getShort(poolStart).toInt() and 0xFFFF
        require(poolType == CHUNK_STRING_POOL) { "Expected string pool chunk right after XML header" }

        val poolHeaderSize = buf.getShort(poolStart + 2).toInt() and 0xFFFF
        val poolChunkSize = buf.getInt(poolStart + 4)
        val stringCount = buf.getInt(poolStart + 8)
        val styleCount = buf.getInt(poolStart + 12)
        val flags = buf.getInt(poolStart + 16)
        val stringsStart = buf.getInt(poolStart + 20)
        val stylesStart = buf.getInt(poolStart + 24)
        val isUtf8 = (flags and UTF8_FLAG) != 0

        if (styleCount != 0 || stylesStart != 0) {
            // Extremely rare for AndroidManifest.xml; bail out rather than risk corrupting it.
            throw UnsupportedOperationException(
                "This manifest has styled strings; binary patching is not supported for it."
            )
        }

        // Offsets table: stringCount * 4 bytes, right after the pool header.
        val offsetsPos = poolStart + poolHeaderSize
        val offsets = IntArray(stringCount) { buf.getInt(offsetsPos + it * 4) }

        val dataBase = poolStart + stringsStart
        val dataEnd = poolStart + poolChunkSize
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val absOffset = dataBase + offsets[i]
            strings.add(if (isUtf8) readUtf8String(buf, absOffset) else readUtf16String(buf, absOffset))
        }

        var changed = false
        val oldDot = "$oldPackage."
        val oldDollar = "$oldPackage$"
        for (i in strings.indices) {
            val s = strings[i]
            val newS = when {
                s == oldPackage -> newPackage
                s.startsWith(oldDot) -> newPackage + "." + s.substring(oldDot.length)
                s.startsWith(oldDollar) -> newPackage + "$" + s.substring(oldDollar.length)
                else -> null
            }
            if (newS != null) {
                strings[i] = newS
                changed = true
            }
        }
        if (!changed) return manifestBytes

        // ---- Rebuild the string pool chunk from scratch ----
        val newOffsets = IntArray(stringCount)
        val dataOut = ByteArrayOutputStream()
        for (i in 0 until stringCount) {
            newOffsets[i] = dataOut.size()
            writeString(dataOut, strings[i], isUtf8)
        }
        // The string data region is 4-byte aligned per the AOSP writer.
        while (dataOut.size() % 4 != 0) dataOut.write(0)
        val newStringDataBytes = dataOut.toByteArray()

        val newOffsetsTableSize = stringCount * 4
        val newPoolHeaderSize = poolHeaderSize // header layout itself doesn't change size
        val newStringsStart = newPoolHeaderSize + newOffsetsTableSize
        val newPoolChunkSize = newStringsStart + newStringDataBytes.size

        val newPool = ByteArrayOutputStream()
        val poolHeader = ByteBuffer.allocate(newPoolHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        poolHeader.putShort(0, CHUNK_STRING_POOL.toShort())
        poolHeader.putShort(2, poolHeaderSize.toShort())
        poolHeader.putInt(4, newPoolChunkSize)
        poolHeader.putInt(8, stringCount)
        poolHeader.putInt(12, 0) // styleCount
        poolHeader.putInt(16, flags) // keep original flags (UTF8_FLAG / SORTED_FLAG) intact
        poolHeader.putInt(20, newStringsStart)
        poolHeader.putInt(24, 0) // stylesStart
        newPool.write(poolHeader.array())

        val offsetsBuf = ByteBuffer.allocate(newOffsetsTableSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until stringCount) offsetsBuf.putInt(i * 4, newOffsets[i])
        newPool.write(offsetsBuf.array())
        newPool.write(newStringDataBytes)

        // ---- Assemble the full document: [XML header][new pool][everything after old pool] ----
        val tail = manifestBytes.copyOfRange(poolStart + poolChunkSize, manifestBytes.size)
        val newTopChunkSize = topHeaderSize + newPool.size() + tail.size

        val out = ByteArrayOutputStream(newTopChunkSize)
        out.write(manifestBytes, 0, topHeaderSize)
        out.write(newPool.toByteArray())
        out.write(tail)
        val result = out.toByteArray()

        // Patch the top-level chunk size field (offset 4, u32 LE).
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, newTopChunkSize)

        return result
    }

    // -- UTF-16 string entry: [len:u16 or u16+u16][chars...][0x0000] --
    private fun readUtf16String(buf: ByteBuffer, offset: Int): String {
        var pos = offset
        var len = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2
        if (len and 0x8000 != 0) {
            val hi = len and 0x7FFF
            val lo = buf.getShort(pos).toInt() and 0xFFFF
            len = (hi shl 16) or lo
            pos += 2
        }
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = buf.getShort(pos + i * 2).toInt().toChar()
        }
        return String(chars)
    }

    private fun readUtf8String(buf: ByteBuffer, offset: Int): String {
        var pos = offset
        // UTF-16 length (character count) - encoded first, we skip it (recomputed on write).
        pos += skipUtf8Len(buf, pos)
        // UTF-8 byte length
        val (byteLen, consumed) = readUtf8LenWithSize(buf, pos)
        pos += consumed
        val bytes = ByteArray(byteLen)
        for (i in 0 until byteLen) bytes[i] = buf.get(pos + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipUtf8Len(buf: ByteBuffer, pos: Int): Int {
        val first = buf.get(pos).toInt() and 0xFF
        return if (first and 0x80 != 0) 2 else 1
    }

    private fun readUtf8LenWithSize(buf: ByteBuffer, pos: Int): Pair<Int, Int> {
        val first = buf.get(pos).toInt() and 0xFF
        return if (first and 0x80 != 0) {
            val second = buf.get(pos + 1).toInt() and 0xFF
            (((first and 0x7F) shl 8) or second) to 2
        } else {
            first to 1
        }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String, utf8: Boolean) {
        if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeUtf8Len(out, value.length)       // UTF-16 char count
            writeUtf8Len(out, bytes.size)          // UTF-8 byte count
            out.write(bytes)
            out.write(0) // terminating NUL byte
        } else {
            writeUtf16Len(out, value.length)
            for (c in value) {
                out.write(c.code and 0xFF)
                out.write((c.code shr 8) and 0xFF)
            }
            out.write(0); out.write(0) // terminating NUL char
        }
    }

    private fun writeUtf8Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len shr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }

    private fun writeUtf16Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7FFF) {
            val hi = ((len shr 16) and 0x7FFF) or 0x8000
            val lo = len and 0xFFFF
            out.write(hi and 0xFF); out.write((hi shr 8) and 0xFF)
            out.write(lo and 0xFF); out.write((lo shr 8) and 0xFF)
        } else {
            out.write(len and 0xFF); out.write((len shr 8) and 0xFF)
        }
    }
}
