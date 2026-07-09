package com.oxygens.core.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * VirtualStorage (plan §4.5).
 *
 * Redirects per-clone `getFilesDir()`/`getCacheDir()`/`getDatabasePath()`/named-dir
 * lookups into `<host-app-private-dir>/virtual/clone_<id>/<guestPackageName>/...`.
 *
 * Deliberately Java-level only, per the plan: "intercept the getters, don't intercept
 * the filesystem." Nothing here touches raw file descriptors or syscalls — it just
 * computes paths and creates directories, and [com.oxygens.core.virtual.vwms.GuestContextWrapper]
 * is what actually makes a guest Context's getters return these paths instead of the
 * host's.
 *
 * Scoped storage (Android 10+) limitation (plan §4.5, §7): this class only manages
 * paths under Oxygen S's own private app directory. It intentionally does NOT attempt
 * to give guest apps transparent access to shared/MediaStore storage — that needs a
 * deliberate, separate "share into clone" feature, not silent passthrough.
 */
class VirtualStorage(private val hostContext: Context) {

    private fun cloneRoot(cloneId: Int): File =
        File(hostContext.filesDir, "virtual/clone_$cloneId").apply { mkdirs() }

    private fun packageRoot(cloneId: Int, guestPackageName: String): File =
        File(cloneRoot(cloneId), guestPackageName).apply { mkdirs() }

    fun filesDir(cloneId: Int, guestPackageName: String): File =
        File(packageRoot(cloneId, guestPackageName), "files").apply { mkdirs() }

    fun cacheDir(cloneId: Int, guestPackageName: String): File =
        File(packageRoot(cloneId, guestPackageName), "cache").apply { mkdirs() }

    fun databaseFile(cloneId: Int, guestPackageName: String, name: String): File {
        val dbDir = File(packageRoot(cloneId, guestPackageName), "databases").apply { mkdirs() }
        return File(dbDir, name)
    }

    fun namedDir(cloneId: Int, guestPackageName: String, name: String): File =
        File(packageRoot(cloneId, guestPackageName), name).apply { mkdirs() }

    fun apkDir(cloneId: Int, guestPackageName: String): File =
        File(packageRoot(cloneId, guestPackageName), "apk").apply { mkdirs() }

    fun nativeLibDir(cloneId: Int, guestPackageName: String): File =
        File(packageRoot(cloneId, guestPackageName), "lib").apply { mkdirs() }

    /** Copies the guest APK into this clone's private apk dir and returns the path. */
    fun extractGuestApk(cloneId: Int, guestPackageName: String, sourceApk: File): String {
        val dest = File(apkDir(cloneId, guestPackageName), "base.apk")
        sourceApk.inputStream().use { input: InputStream ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return dest.absolutePath
    }

    /** PNG file where the guest app's launcher icon is stored at install time. */
    fun iconFile(cloneId: Int, guestPackageName: String): File =
        File(packageRoot(cloneId, guestPackageName), "icon.png")

    fun wipeClone(cloneId: Int) {
        cloneRoot(cloneId).deleteRecursively()
    }

    /** Best-effort size (bytes) of everything stored for a clone, for a "storage
     *  usage per clone" settings screen (plan §5.3, Phase 3). */
    fun sizeOf(cloneId: Int): Long = cloneRoot(cloneId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
