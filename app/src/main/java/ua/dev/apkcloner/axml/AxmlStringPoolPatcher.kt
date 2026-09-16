package ua.dev.apkcloner.axml

import ua.dev.apkcloner.util.Logger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Patches package-name-derived strings directly inside a *compiled* (binary) AndroidManifest.xml,
 * without decompiling it back to text (no apktool / aapt needed on-device).
 *
 * How it works
 * ------------
 * A compiled Android binary XML file (AXML) stores every string used anywhere in the document
 * (element names, attribute names, attribute values, namespace URIs...) once, in a single global
 * "String Pool" chunk near the start of the file. Every element/attribute then just references a
 * string by its index into that pool.
 *
 * Two independent passes run over that pool:
 *
 * 1. Package-name rewrite: ONLY the string that is EXACTLY equal to the old package name (the
 *    manifest's own package="..." attribute) is renamed. Strings that merely start with the
 *    package name are deliberately left untouched — those are almost always fully-qualified
 *    class-name references into the .dex (e.g. "oldPackage.MainActivity"), which this tool
 *    never patches; renaming the manifest reference without renaming the actual class would
 *    make Android look for a class that no longer exists, crashing the clone at launch.
 *
 * 2. Uniquification: <provider android:authorities="..."> values AND <permission
 *    android:name="..."> declarations are resolved precisely (not guessed from text) via the
 *    Resource Map chunk (attribute resource ids 0x01010026 and 0x01010003, the latter scoped to
 *    only the <permission> element) and each gets a random per-clone suffix appended. This is
 *    what actually prevents INSTALL_FAILED_CONFLICTING_PROVIDER and
 *    INSTALL_FAILED_DUPLICATE_PERMISSION, and works regardless of whether the value happens to be
 *    package-prefixed or a completely unrelated fixed string from some library.
 *
 * Binary format reference: frameworks/base ResourceTypes.h/.cpp (ResStringPool_header,
 * ResChunk_header, ResXMLTree_node, ResXMLTree_attrExt, ResXMLTree_attribute, Res_value).
 * AndroidManifest.xml compiled by aapt2 has styleCount == 0, so we don't preserve style span data.
 */
object AxmlStringPoolPatcher {

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 1 shl 8

    private const val ATTR_AUTHORITIES_RESID = 0x01010018 // android:authorities
    private const val ATTR_NAME_RESID = 0x01010003 // android:name
    private const val TYPE_STRING = 0x03 // Res_value.dataType for a string reference

    /**
     * @param manifestBytes raw bytes of the compiled AndroidManifest.xml (or resources.arsc) entry
     * @param oldPackage    original applicationId, e.g. "com.example.app"
     * @param newPackage    desired applicationId, e.g. "com.example.app.clone1"
     * @param renamePrefixedStrings If true, ALSO renames any string that merely starts with
     *   "<oldPackage>." / "<oldPackage>$" (not just exact matches). Safe for resources.arsc,
     *   whose string pool holds plain resource VALUES (UI text, authority strings baked from
     *   ${applicationId} placeholders, etc.) — never .dex class-name references. Must stay false
     *   for AndroidManifest.xml, where such strings are typically fully-qualified class names
     *   (android:name of activities/services/providers) that this tool does not relocate in the
     *   .dex; renaming the reference without the class would crash the clone at launch.
     * @return patched bytes, or the original bytes unchanged if nothing needed changing
     */
    fun patchPackageName(
        manifestBytes: ByteArray,
        oldPackage: String,
        newPackage: String,
        renamePrefixedStrings: Boolean = false
    ): ByteArray {
        val buf = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Top-level chunk: for AndroidManifest.xml this is ResXMLTree_header (0x0003); for
        // resources.arsc it's ResTable_header (0x0002). Both are followed immediately by a
        // String Pool chunk, which is the only thing the rest of this function relies on.
        val topType = buf.getShort(0).toInt() and 0xFFFF
        require(topType == 0x0003 || topType == 0x0002) {
            "Not a compiled binary resource chunk (unexpected root chunk type $topType)"
        }
        val topHeaderSize = buf.getShort(2).toInt() and 0xFFFF

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
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val absOffset = dataBase + offsets[i]
            strings.add(if (isUtf8) readUtf8String(buf, absOffset) else readUtf16String(buf, absOffset))
        }
        val diagnosticIndices = strings.indices.filter {
            val s = strings[it]
            s.contains("startup", ignoreCase = true) || s.contains("fileprovider", ignoreCase = true)
        }
        Logger.log(
            "AxmlPatcher",
            "before: renamePrefixed=$renamePrefixedStrings matches=${diagnosticIndices.map { it to strings[it] }}"
        )

        // ---- Pass 2 prep: find every string-pool index that needs a uniquifying suffix
        // (android:authorities values, and <permission> android:name declarations) ----
        val authorityIndices = findAuthorityStringIndices(buf, manifestBytes.size, poolStart, poolChunkSize, stringCount, strings)
        Logger.log(
            "AxmlPatcher",
            "authorityIndices found=${authorityIndices.size} values=${authorityIndices.map { strings.getOrNull(it) }}"
        )
        val authoritySuffix = ".c" + Random.nextInt(0x1000, 0xFFFF).toString(16)

        var changed = false
        val oldDot = "$oldPackage."
        val oldDollar = "$oldPackage$"
        for (i in strings.indices) {
            var s = strings[i]
            // Exact match (the package="..." attribute, or a resources.arsc string value that
            // is literally just the package name) is always safe to rename.
            if (s == oldPackage) {
                s = newPackage
                changed = true
            } else if (renamePrefixedStrings) {
                // Only enabled for resources.arsc (see kdoc above) — never for
                // AndroidManifest.xml, where a prefixed string is almost always a fully
                // qualified .dex class-name reference that must NOT be rewritten here.
                if (s.startsWith(oldDot)) {
                    s = newPackage + "." + s.substring(oldDot.length)
                    changed = true
                } else if (s.startsWith(oldDollar)) {
                    s = newPackage + "$" + s.substring(oldDollar.length)
                    changed = true
                }
            }
            if (i in authorityIndices) {
                s += authoritySuffix
                changed = true
            }
            strings[i] = s
        }
        Logger.log(
            "AxmlPatcher",
            "after: matches=${diagnosticIndices.map { it to strings[it] }}"
        )
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
        val newStringsStart = poolHeaderSize + newOffsetsTableSize
        val newPoolChunkSize = newStringsStart + newStringDataBytes.size

        val newPool = ByteArrayOutputStream()
        val poolHeader = ByteBuffer.allocate(poolHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
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

    /**
     * Walks the Resource Map + XML node section (both untouched by the rename pass, since that
     * only rewrites string *bytes*, never indices) to find every string-pool index that needs a
     * uniquifying suffix: android:authorities values (any element), and android:name values that
     * belong specifically to a top-level <permission> declaration (NOT any other element — most
     * elements use android:name too, e.g. activities/services/providers, and those must be left
     * alone since they're .dex class references).
     */
    private fun findAuthorityStringIndices(
        buf: ByteBuffer,
        totalSize: Int,
        poolStart: Int,
        poolChunkSize: Int,
        stringCount: Int,
        strings: List<String>
    ): Set<Int> {
        var pos = poolStart + poolChunkSize
        if (pos >= totalSize) return emptySet()

        // Optional Resource Map chunk: maps string-pool index -> Android resource id, one
        // int per string, covering the first N (attribute-name) strings in the pool.
        var authoritiesNameIndex = -1
        var androidNameIndex = -1
        val chunkType = buf.getShort(pos).toInt() and 0xFFFF
        if (chunkType == CHUNK_RESOURCE_MAP) {
            val chunkSize = buf.getInt(pos + 4)
            val idCount = (chunkSize - 8) / 4
            for (i in 0 until minOf(idCount, stringCount)) {
                val resId = buf.getInt(pos + 8 + i * 4)
                if (resId == ATTR_AUTHORITIES_RESID) authoritiesNameIndex = i
                if (resId == ATTR_NAME_RESID) androidNameIndex = i
            }
            pos += chunkSize
        }
        if (authoritiesNameIndex < 0 && androidNameIndex < 0) return emptySet()

        val result = HashSet<Int>()
        while (pos < totalSize) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size <= 0) break // corrupt/unexpected, stop rather than loop forever

            if (type == CHUNK_START_ELEMENT) {
                // ResXMLTree_node common header is headerSize bytes (line number + comment),
                // immediately followed by ResXMLTree_attrExt.
                val attrExtStart = pos + headerSize
                val elementNameIndex = buf.getInt(attrExtStart + 4)
                val elementName = strings.getOrNull(elementNameIndex)
                val attributeStart = buf.getShort(attrExtStart + 8).toInt() and 0xFFFF
                val attributeSize = buf.getShort(attrExtStart + 10).toInt() and 0xFFFF
                val attributeCount = buf.getShort(attrExtStart + 12).toInt() and 0xFFFF
                val firstAttr = attrExtStart + attributeStart

                for (a in 0 until attributeCount) {
                    val attrOffset = firstAttr + a * attributeSize
                    val nameIndex = buf.getInt(attrOffset + 4)

                    val isAuthorities = nameIndex == authoritiesNameIndex
                    // Only treat android:name as something to uniquify when it's the name of a
                    // <permission> DEFINITION — a custom permission can only be declared by one
                    // package on the device (INSTALL_FAILED_DUPLICATE_PERMISSION otherwise).
                    // <uses-permission>, activities, services, providers etc. all reuse the same
                    // android:name attribute id but must NOT be touched.
                    val isPermissionDefName = nameIndex == androidNameIndex && elementName == "permission"
                    if (!isAuthorities && !isPermissionDefName) continue

                    val rawValueIndex = buf.getInt(attrOffset + 8)
                    if (rawValueIndex >= 0) result.add(rawValueIndex)

                    // Res_value starts at attrOffset+12: size(2) res0(1) dataType(1) data(4)
                    val dataType = buf.get(attrOffset + 12 + 3).toInt() and 0xFF
                    val data = buf.getInt(attrOffset + 12 + 4)
                    if (dataType == TYPE_STRING && data >= 0) result.add(data)
                }
            }
            pos += size
        }
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
