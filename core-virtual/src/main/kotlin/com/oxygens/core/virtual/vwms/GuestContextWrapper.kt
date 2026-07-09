package com.oxygens.core.virtual.vwms

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import com.oxygens.core.storage.VirtualStorage

/**
 * VWMS/display (plan §4.3).
 *
 * The stub Activity provides a real window — we don't reimplement window management.
 * The actual work here is making sure the guest's Context resolves Resources/Assets
 * against the *guest's* APK, not the host's, and that file-path getters resolve into
 * the per-clone storage directory (delegated to [VirtualStorage], which does this at
 * the Java level, not by touching the filesystem — see core-storage).
 */
class GuestContextWrapper(
    base: Context,
    private val guestResources: Resources,
    private val guestAssets: AssetManager,
    private val cloneId: Int,
    private val guestPackageName: String,
    private val storage: VirtualStorage,
) : ContextWrapper(base) {

    override fun getResources(): Resources = guestResources

    override fun getAssets(): AssetManager = guestAssets

    override fun getPackageName(): String = guestPackageName

    override fun getFilesDir() = storage.filesDir(cloneId, guestPackageName)

    override fun getCacheDir() = storage.cacheDir(cloneId, guestPackageName)

    override fun getDatabasePath(name: String) = storage.databaseFile(cloneId, guestPackageName, name)

    override fun getDir(name: String, mode: Int) = storage.namedDir(cloneId, guestPackageName, name)
}
