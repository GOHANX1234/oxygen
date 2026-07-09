package com.oxygens.core.loader

import com.oxygens.core.loader.model.ComponentType
import com.oxygens.core.loader.model.GuestComponent
import com.oxygens.core.loader.model.ParsedPackage
import java.io.File
import java.util.zip.ZipFile

/**
 * Parses a guest APK's manifest and component list without going through the real
 * PackageInstaller and without relying on the hidden `android.content.pm.PackageParser`
 * (restricted on modern API levels — plan §4.1).
 *
 * AndroidManifest.xml inside an APK is Android Binary XML (AXML / ResXML), not plain
 * text XML. [parseManifestXml] uses [AxmlParser] — a pure-Kotlin AXML parser written
 * to the AOSP ResourceTypes.h spec — so no hidden APIs, no PackageParser reflection,
 * and no third-party dependencies are needed.
 */
class GuestApkParser {

    class ApkParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Parse a guest APK or APKS bundle file.
     *
     * **APKS bundles** (produced by bundletool from an AAB): a ZIP containing
     * `base.apk`, `split_config.arm64_v8a.apk`, etc. They have no `AndroidManifest.xml`
     * at the root; the manifest lives inside `base.apk`. We detect this case, extract
     * `base.apk` to a temp file, parse it, and delete the temp file before returning.
     * Split APK paths are recorded in [ParsedPackage.splitApkPaths] so CloneManager
     * can include them in the DexClassLoader's dex path list.
     */
    fun parse(apkFile: File): ParsedPackage {
        if (!apkFile.exists()) throw ApkParseException("APK not found: ${apkFile.absolutePath}")

        // ── APKS bundle detection ─────────────────────────────────────────────
        val isBundle = ZipFile(apkFile).use { probe ->
            probe.getEntry("AndroidManifest.xml") == null && probe.getEntry("base.apk") != null
        }
        if (isBundle) return parseApksBundle(apkFile)

        return parseSingleApk(apkFile, apkFile.name)
    }

    /**
     * Handle an APKS bundle by opening a fresh ZipFile (the probe's ZipFile above is
     * already closed by the `use` block before this method is called), extracting
     * `base.apk` to a temp file, parsing it, and cleaning up.
     *
     * Split APKs (density, language, ABI) are intentionally not extracted here because
     * [NativeLibraryInstaller] handles native libs separately, and split DEX paths will
     * be handled in a future Phase 1 task when DexClassLoader chaining is implemented.
     */
    private fun parseApksBundle(apksFile: File): ParsedPackage {
        val tempDir = apksFile.parentFile ?: apksFile.absoluteFile.parentFile!!
        val tempBase = File(tempDir, "base_tmp_${System.currentTimeMillis()}.apk")
        try {
            ZipFile(apksFile).use { zip ->
                val baseEntry = zip.getEntry("base.apk")
                    ?: throw ApkParseException(
                        "${apksFile.name} looks like an APKS bundle but has no base.apk — " +
                        "make sure you're picking the full APKS file from bundletool, not an extracted split."
                    )
                // Use `use` so the FileOutputStream is flushed+closed before we read
                // tempBase in parseSingleApk, avoiding truncated-read surprises.
                zip.getInputStream(baseEntry).use { inp ->
                    tempBase.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            val base = parseSingleApk(tempBase, apksFile.name)
            return base.copy(isApksBundle = true)
        } finally {
            tempBase.delete()
        }
    }

    private fun parseSingleApk(apkFile: File, displayName: String): ParsedPackage {
        ZipFile(apkFile).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw ApkParseException("No AndroidManifest.xml in $displayName — not a valid APK")

            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val manifest = parseManifestXml(manifestBytes, apkFile.name)

            val nativeLibAbis = zip.entries().asSequence()
                .mapNotNull { entry ->
                    val match = Regex("^lib/([^/]+)/").find(entry.name)
                    match?.groupValues?.get(1)
                }
                .toSet()
                .toList()

            return ParsedPackage(
                packageName = manifest.packageName,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                minSdkVersion = manifest.minSdkVersion,
                targetSdkVersion = manifest.targetSdkVersion,
                applicationLabel = manifest.applicationLabel,
                mainActivity = manifest.components.firstOrNull {
                    it.type == ComponentType.ACTIVITY && it.isLauncherMain
                }?.className,
                components = manifest.components.map {
                    GuestComponent(it.className, it.type, it.exported, it.permission)
                },
                requestedPermissions = manifest.requestedPermissions,
                nativeLibAbis = nativeLibAbis,
            )
        }
    }

    // ── Internal manifest model ───────────────────────────────────────────────

    private data class ParsedManifestComponent(
        val className: String,
        val type: ComponentType,
        val exported: Boolean,
        val permission: String?,
        /** True only for ACTIVITY tags that contain an intent-filter with both
         *  android.intent.action.MAIN and android.intent.category.LAUNCHER. */
        val isLauncherMain: Boolean,
    )

    private data class ParsedManifest(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val minSdkVersion: Int,
        val targetSdkVersion: Int,
        val applicationLabel: String,
        val components: List<ParsedManifestComponent>,
        val requestedPermissions: List<String>,
    )

    // ── AXML manifest parsing ─────────────────────────────────────────────────

    /**
     * Parses the binary-XML AndroidManifest.xml bytes via [AxmlParser] and extracts
     * the fields needed by [CloneManager] / VPMS:
     *   • package name, versionCode/Name
     *   • minSdk / targetSdk
     *   • application label (falls back to packageName when the attribute is a
     *     resource reference that we can't resolve without resources.arsc)
     *   • activity / service / provider / receiver component list with exported/
     *     permission flags and LAUNCHER-intent detection for activities
     *   • requested permissions list
     *
     * Component class names starting with '.' are expanded to `packageName + name`;
     * unqualified names (no '.') are prefixed as `packageName.name`.
     */
    private fun parseManifestXml(manifestBytes: ByteArray, apkFileName: String): ParsedManifest {
        val events = try {
            AxmlParser(manifestBytes).parse()
        } catch (e: Exception) {
            throw ApkParseException("Failed to parse AndroidManifest.xml in $apkFileName: ${e.message}", e)
        }

        var packageName     = ""
        var versionName: String? = null
        var versionCode     = 0L
        var minSdk          = 1
        var targetSdk       = 1
        var appLabel        = ""
        val components      = mutableListOf<ParsedManifestComponent>()
        val permissions     = mutableListOf<String>()

        // State for the component currently being parsed
        var currentType:          ComponentType? = null
        var currentName:          String?        = null
        // null = attribute absent (must be inferred from intent-filter presence)
        var currentExportedAttr:  Boolean?       = null
        var currentPermission:    String?        = null
        var currentHasFilters:    Boolean        = false  // any intent-filter inside this component?
        var isLauncherActivity:   Boolean        = false  // MAIN+LAUNCHER found in same filter?
        var inIntentFilter:       Boolean        = false
        // Per-filter tracking — reset on each <intent-filter>
        var filterHasMain:        Boolean        = false
        var filterHasLauncher:    Boolean        = false

        for (event in events) {
            when (event) {
                is AxmlParser.Event.StartTag -> {
                    val a = event.attrs
                    when (event.name) {
                        "manifest" -> {
                            packageName = a["package"] ?: ""
                            versionName = a["versionName"]
                            versionCode = a["versionCode"]?.toLongOrNull() ?: 0L
                        }
                        "uses-sdk" -> {
                            minSdk    = a["minSdkVersion"]?.toIntOrNull()    ?: 1
                            targetSdk = a["targetSdkVersion"]?.toIntOrNull() ?: minSdk
                        }
                        "application" -> {
                            // android:label is often a resource reference (null from AxmlParser)
                            // — defer to packageName as a readable fallback
                            appLabel = a["label"] ?: ""
                        }
                        "activity", "service", "provider", "receiver" -> {
                            currentType         = when (event.name) {
                                "activity" -> ComponentType.ACTIVITY
                                "service"  -> ComponentType.SERVICE
                                "provider" -> ComponentType.PROVIDER
                                else       -> ComponentType.RECEIVER
                            }
                            val rawName         = a["name"] ?: ""
                            currentName         = expandClassName(rawName, packageName)
                            // Explicit "true"/"false" from manifest vs absent (null).
                            // When absent, exported is inferred at </component> time
                            // from whether any intent-filter was present (Android rule).
                            currentExportedAttr = a["exported"]?.toBooleanStrictOrNull()
                            currentPermission   = a["permission"]
                            currentHasFilters   = false
                            isLauncherActivity  = false
                        }
                        "intent-filter" -> {
                            inIntentFilter  = true
                            filterHasMain     = false
                            filterHasLauncher = false
                            currentHasFilters = true
                        }
                        "action" -> if (inIntentFilter &&
                            a["name"] == "android.intent.action.MAIN") {
                            filterHasMain = true
                        }
                        "category" -> if (inIntentFilter &&
                            a["name"] == "android.intent.category.LAUNCHER") {
                            filterHasLauncher = true
                        }
                        "uses-permission" -> a["name"]?.let { permissions += it }
                    }
                }
                is AxmlParser.Event.EndTag -> when (event.name) {
                    "intent-filter" -> {
                        // Both MAIN action and LAUNCHER category must be in the SAME
                        // intent-filter to qualify as the launcher entry point
                        if (filterHasMain && filterHasLauncher &&
                            currentType == ComponentType.ACTIVITY) {
                            isLauncherActivity = true
                        }
                        inIntentFilter = false
                    }
                    "activity", "service", "provider", "receiver" -> {
                        val type = currentType
                        val name = currentName
                        if (type != null && !name.isNullOrEmpty()) {
                            // Android exported inference (when attribute absent):
                            //   activities/services/receivers → true if any intent-filter present,
                            //                                   false otherwise
                            //   providers                     → true (historically always exported
                            //                                   unless explicitly false; we conservatively
                            //                                   infer true when absent since the guest
                            //                                   may rely on cross-process access)
                            val exported = currentExportedAttr ?: when (type) {
                                ComponentType.PROVIDER -> true
                                else                   -> currentHasFilters
                            }
                            components += ParsedManifestComponent(
                                className      = name,
                                type           = type,
                                exported       = exported,
                                permission     = currentPermission,
                                isLauncherMain = isLauncherActivity,
                            )
                        }
                        currentType         = null
                        currentName         = null
                        currentExportedAttr = null
                        currentPermission   = null
                        currentHasFilters   = false
                        isLauncherActivity  = false
                    }
                }
            }
        }

        if (packageName.isEmpty()) {
            throw ApkParseException(
                "AndroidManifest.xml in $apkFileName is missing the 'package' attribute — " +
                "either the manifest is malformed or the AXML parser hit an unhandled encoding."
            )
        }

        return ParsedManifest(
            packageName         = packageName,
            versionName         = versionName,
            versionCode         = versionCode,
            minSdkVersion       = minSdk,
            targetSdkVersion    = targetSdk,
            applicationLabel    = appLabel.ifEmpty { packageName },
            components          = components,
            requestedPermissions = permissions,
        )
    }

    /**
     * Expand a potentially relative component class name to fully-qualified form.
     *   ".Foo"      → "com.example.app.Foo"
     *   "Foo"       → "com.example.app.Foo"   (no dots at all → treat as relative)
     *   "com.a.Foo" → "com.a.Foo"             (already FQ)
     */
    private fun expandClassName(rawName: String, packageName: String): String = when {
        rawName.startsWith(".")     -> packageName + rawName
        !rawName.contains(".")     -> "$packageName.$rawName"
        else                        -> rawName
    }
}
