package com.oxygens.core.loader

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Builds a per-clone [DexClassLoader] for a guest APK (plan §4.4).
 *
 * Parent classloader is the host's own classloader, so guest classes can resolve
 * shared framework/AndroidX classes normally, while guest-specific classes resolve
 * first against the guest's own dex/resources. This mirrors how VirtualApp/DroidPlugin
 * set up their guest classloaders (plan §8).
 */
class GuestClassLoaderFactory(private val hostContext: Context) {

    fun create(
        guestApkPath: String,
        optimizedDirectory: File,
        nativeLibraryDir: File,
    ): DexClassLoader {
        optimizedDirectory.mkdirs()
        nativeLibraryDir.mkdirs()
        return DexClassLoader(
            guestApkPath,
            optimizedDirectory.absolutePath,
            nativeLibraryDir.absolutePath,
            hostContext.classLoader,
        )
    }
}
