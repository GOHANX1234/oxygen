package com.oxygens.registry

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent clone registry row (plan §3: "Clone registry: package name, install
 * source, storage path, display name/icon override").
 *
 * Schema versions:
 *   v1 → v2: added nullable iconPath column.
 *   v2 → v3: added nullable mainActivity column (fully-qualified launcher Activity name,
 *             null when the guest APK declares no MAIN+LAUNCHER activity).
 */
@Entity(tableName = "clones")
data class CloneEntity(
    @PrimaryKey val cloneId: Int,
    val guestPackageName: String,
    val displayName: String,
    val guestApkPath: String,
    val dataDir: String,
    val processGroup: String,
    val installedAtMillis: Long,
    /** Absolute path to a PNG icon extracted from the guest APK, or null if extraction
     *  failed (e.g. APK has no extractable icon). UI falls back to a generic placeholder. */
    val iconPath: String? = null,
    /** Fully-qualified class name of the guest's launcher Activity (the one with
     *  MAIN + LAUNCHER intent-filter), or null when the APK declares none.
     *  Populated by GuestApkParser.mainActivity at install time. */
    val mainActivity: String? = null,
)
