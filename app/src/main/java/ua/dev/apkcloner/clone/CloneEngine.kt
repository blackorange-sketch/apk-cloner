package ua.dev.apkcloner.clone

import ua.dev.apkcloner.axml.AxmlStringPoolPatcher
import ua.dev.apkcloner.util.Logger
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites a source APK into a "clone" APK with a different applicationId, without decompiling
 * dex/resources. Only binary string pools are touched — the manifest's own `package` attribute
 * (renamed exactly) and every `android:authorities` value (uniquified) — in TWO places:
 *
 * 1. AndroidManifest.xml — covers the common case where authorities/permissions are literal
 *    text in the manifest itself (e.g. "${applicationId}.fileprovider" resolved at build time).
 * 2. resources.arsc — some apps declare android:authorities="@string/some_id", i.e. the actual
 *    package-prefixed text lives as a plain STRING RESOURCE VALUE in resources.arsc instead of
 *    inline in the manifest. resources.arsc's top-level string pool uses the exact same binary
 *    sub-format as AndroidManifest.xml's, so the same patcher is reused for it. If resources.arsc
 *    happens to contain styled strings (rare, but more likely there than in a manifest) the
 *    patcher throws — that's caught here and we simply leave resources.arsc untouched rather
 *    than failing the whole clone.
 *
 * Note on dex code: app code that calls Context#getPackageName() will correctly see the NEW
 * package name at runtime (PackageManager derives it from the installed manifest, not from
 * anything baked into the dex). Only code that hardcodes the literal old package name as a
 * string constant (rare) would need dex-level patching, which this minimalist tool does not do.
 */
object CloneEngine {

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val RESOURCES_ENTRY = "resources.arsc"

    /**
     * @param sourceApk   original APK pulled from the device (e.g. ApplicationInfo.sourceDir)
     * @param outputApk   where to write the patched, UNSIGNED apk
     * @param oldPackage  original applicationId
     * @param newPackage  desired applicationId for the clone
     */
    fun createClone(sourceApk: File, outputApk: File, oldPackage: String, newPackage: String) {
        ZipFile(sourceApk).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                ?: error("AndroidManifest.xml not found in APK")
            val originalManifestBytes = zip.getInputStream(manifestEntry).readBytes()
            val patchedManifestBytes = AxmlStringPoolPatcher.patchPackageName(
                originalManifestBytes, oldPackage, newPackage
            )
            Logger.log(
                "CloneEngine",
                "AndroidManifest.xml patched: changed=${!patchedManifestBytes.contentEquals(originalManifestBytes)}"
            )

            val resourcesEntry = zip.getEntry(RESOURCES_ENTRY)
            val patchedResourcesBytes: ByteArray? = resourcesEntry?.let { entry ->
                val original = zip.getInputStream(entry).use { it.readBytes() }
                try {
                    val patched = AxmlStringPoolPatcher.patchPackageName(original, oldPackage, newPackage)
                    Logger.log(
                        "CloneEngine",
                        "resources.arsc patched: changed=${!patched.contentEquals(original)}"
                    )
                    patched
                } catch (t: Throwable) {
                    // e.g. styled strings in resources.arsc's global pool — fall back to
                    // leaving it untouched rather than failing the whole clone.
                    Logger.log("CloneEngine", "resources.arsc left unpatched: ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
            }

            ZipOutputStream(outputApk.outputStream().buffered()).use { zos ->
                val entries = zip.entries().toList().sortedBy { it.name }
                for (entry in entries) {
                    if (entry.isDirectory) continue

                    val bytes = when (entry.name) {
                        MANIFEST_ENTRY -> patchedManifestBytes
                        RESOURCES_ENTRY -> patchedResourcesBytes ?: zip.getInputStream(entry).use { it.readBytes() }
                        else -> zip.getInputStream(entry).use { it.readBytes() }
                    }

                    val newEntry = ZipEntry(entry.name)
                    // Keep resources.arsc (and anything already STORED) uncompressed: some
                    // Android versions expect resources.arsc to be stored, not deflated.
                    val keepStored = entry.method == ZipEntry.STORED || entry.name == RESOURCES_ENTRY
                    if (keepStored) {
                        val crc = CRC32().apply { update(bytes) }
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = bytes.size.toLong()
                        newEntry.compressedSize = bytes.size.toLong()
                        newEntry.crc = crc.value
                    } else {
                        newEntry.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(newEntry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }
}
