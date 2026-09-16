package ua.dev.apkcloner.clone

import ua.dev.apkcloner.axml.AxmlStringPoolPatcher
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites a source APK into a "clone" APK with a different applicationId, without decompiling
 * dex/resources. Only AndroidManifest.xml's binary string pool is touched (package name,
 * ContentProvider authorities, permission strings that share the package prefix).
 *
 * Note on dex code: app code that calls Context#getPackageName() will correctly see the NEW
 * package name at runtime (PackageManager derives it from the installed manifest, not from
 * anything baked into the dex). Only code that hardcodes the literal old package name as a
 * string constant (rare) would need dex-level patching, which this minimalist tool does not do.
 */
object CloneEngine {

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

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

            ZipOutputStream(outputApk.outputStream().buffered()).use { zos ->
                val entries = zip.entries().toList().sortedBy { it.name }
                for (entry in entries) {
                    if (entry.isDirectory) continue

                    val bytes = if (entry.name == MANIFEST_ENTRY) {
                        patchedManifestBytes
                    } else {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }

                    val newEntry = ZipEntry(entry.name)
                    // Keep resources.arsc (and anything already STORED) uncompressed: some
                    // Android versions expect resources.arsc to be stored, not deflated.
                    val keepStored = entry.method == ZipEntry.STORED || entry.name == "resources.arsc"
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
