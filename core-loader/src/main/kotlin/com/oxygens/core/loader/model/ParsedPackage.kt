package com.oxygens.core.loader.model

/**
 * Output of parsing a guest APK's manifest (see GuestApkParser). Lives in core-loader
 * (not core-virtual) so core-loader never needs to depend on core-virtual — CloneManager
 * in core-virtual depends on core-loader, not the other way around.
 */
enum class ComponentType { ACTIVITY, SERVICE, PROVIDER, RECEIVER }

data class GuestComponent(
    val className: String,
    val type: ComponentType,
    val exported: Boolean,
    val permission: String?,
)

data class ParsedPackage(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val applicationLabel: String,
    val mainActivity: String?,
    val components: List<GuestComponent>,
    val requestedPermissions: List<String>,
    val nativeLibAbis: List<String>,
    /** True when the source file was an APKS bundle (zip of split APKs) rather than a
     *  plain APK. Callers may need to handle split extraction differently. */
    val isApksBundle: Boolean = false,
)
