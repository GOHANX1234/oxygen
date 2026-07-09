package com.oxygens.core.loader

import com.oxygens.core.storage.VirtualStorage
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts a guest APK's bundled native libraries (the .so files under lib/<abi>/)
 * into the clone's private native-lib directory, matching only the ABI this device
 * actually supports (plan §4.4: arm64-v8a only — 32-bit guest APKs are an explicit,
 * flagged limitation, not silently supported).
 */
class NativeLibraryInstaller(private val storage: VirtualStorage) {

    class UnsupportedAbiException(message: String) : Exception(message)

    private val supportedAbi = "arm64-v8a"

    fun installFor(cloneId: Int, guestPackageName: String, apkFile: File) {
        val targetDir = storage.nativeLibDir(cloneId, guestPackageName)

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith("lib/$supportedAbi/") && it.name.endsWith(".so") }
                .toList()

            val hasAnyNativeLibs = zip.entries().asSequence().any { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            if (hasAnyNativeLibs && entries.isEmpty()) {
                throw UnsupportedAbiException(
                    "Guest APK ${apkFile.name} bundles native libraries but none for " +
                        "$supportedAbi. 32-bit-only guest APKs are unsupported (plan §7) — " +
                        "surface this to the user rather than installing a broken clone."
                )
            }

            for (entry in entries) {
                val outFile = File(targetDir, entry.name.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile.setExecutable(true, false)
            }
        }
    }
}
