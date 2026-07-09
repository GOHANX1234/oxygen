package com.oxygens.core.virtual.model

/**
 * Runtime view of a single clone. Persisted separately by clone-registry-db;
 * this is the in-memory model VPMS/VAMS operate on.
 *
 * Schema note: mainActivity mirrors the CloneEntity column added in DB v3 — it is the
 * fully-qualified class name of the guest's MAIN+LAUNCHER Activity, null when absent.
 */
data class CloneInfo(
    val cloneId: Int,
    val guestPackageName: String,
    val displayName: String,
    val guestApkPath: String,
    val dataDir: String,
    /** Process group this clone is pinned to, e.g. ":clone_0". Must match a group
     *  declared in AndroidManifest's stub component pool (see StubComponentPool). */
    val processGroup: String,
    val installedAtMillis: Long,
    /** Absolute path to a PNG extracted from the guest APK at install time, or null. */
    val iconPath: String? = null,
    /** Fully-qualified launcher Activity class name, or null when the guest declares none.
     *  Used by CloneListViewModel.launchClone() to build the stub Intent. */
    val mainActivity: String? = null,
)
