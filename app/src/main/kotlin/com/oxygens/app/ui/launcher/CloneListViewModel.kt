package com.oxygens.app.ui.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oxygens.core.loader.GuestApkParser
import com.oxygens.core.loader.NativeLibraryInstaller
import com.oxygens.core.loader.model.ComponentType
import com.oxygens.core.virtual.CloneManager
import com.oxygens.core.virtual.model.CloneInfo
import com.oxygens.core.virtual.vams.StubComponentPool
import com.oxygens.core.virtual.vams.VirtualActivityManagerService.Companion.EXTRA_GUEST_CLASS_NAME
import com.oxygens.core.virtual.vams.VirtualActivityManagerService.Companion.EXTRA_GUEST_CLONE_ID
import com.oxygens.core.virtual.vams.VirtualActivityManagerService.Companion.EXTRA_GUEST_PACKAGE_NAME
import com.oxygens.registry.CloneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Backs the launcher grid (plan §5, Phase 3). Talks to [CloneManager] rather than any
 * virtual-service class directly — the ViewModel should never need to know about
 * VPMS/VAMS internals.
 *
 * Runs in the host UI process (not a ":clone_N" process), so it does not go through
 * VirtualCore.bootstrap — that only matters for guest code, which never runs here.
 */
class CloneListViewModel(application: Application) : AndroidViewModel(application) {

    private val _clones = MutableStateFlow<List<CloneInfo>>(emptyList())
    val clones: StateFlow<List<CloneInfo>> = _clones.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val cloneManager: CloneManager by lazy {
        val storage = com.oxygens.core.storage.VirtualStorage(application)
        val parser = GuestApkParser()
        val nativeLibraryInstaller = NativeLibraryInstaller(storage)
        val registry = com.oxygens.core.virtual.vpms.PackageRegistry()
        val vpms = com.oxygens.core.virtual.vpms.VirtualPackageManagerService(registry)
        val dao = CloneDatabase.getInstance(application).cloneDao()
        CloneManager(
            parser = parser,
            nativeLibraryInstaller = nativeLibraryInstaller,
            storage = storage,
            vpms = vpms,
            cloneDao = dao,
            availableProcessGroups = listOf(":clone_0", ":clone_1"),
        )
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cloneManager.listClones() } }
                .onSuccess { _clones.value = it }
                .onFailure { _error.value = it.message }
        }
    }

    /**
     * Install a guest APK (or APKS bundle).
     *
     * Label and icon are extracted via [PackageManager.getPackageArchiveInfo] before
     * calling [CloneManager] so the tile shows the real app name and icon immediately,
     * even when [android:label] in the manifest is a resource reference (which our AXML
     * parser cannot resolve without also parsing resources.arsc).
     *
     * [onDone] always fires (success or failure) for cache-file cleanup in the caller.
     */
    fun installClone(apkFile: File, displayName: String?, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val pm = getApplication<Application>().packageManager
                    val (resolvedLabel, iconBytes) = extractApkMeta(pm, apkFile)
                    cloneManager.installClone(
                        apkFile      = apkFile,
                        displayName  = displayName ?: resolvedLabel,
                        iconPngBytes = iconBytes,
                    )
                }
            }
                .onSuccess { refresh() }
                .onFailure { _error.value = it.message }
            onDone()
        }
    }

    fun removeClone(cloneId: Int) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cloneManager.removeClone(cloneId) } }
                .onSuccess { refresh() }
                .onFailure { _error.value = it.message }
        }
    }

    /**
     * Launch a cloned app by building a stub Intent directly from the clone's persisted
     * [CloneInfo.mainActivity] and firing it with [Intent.FLAG_ACTIVITY_NEW_TASK].
     *
     * This runs in the host UI process: we do NOT need VirtualCore.bootstrap here.
     * [StubComponentPool] gives us the first available stub Activity slot for the
     * clone's process group — in Phase 1 we always pick the first slot since each
     * process group runs exactly one clone. The stub Activity's onCreate then handles
     * DexClassLoader construction and guest delegation (see StubActivity).
     *
     * Errors (no main activity, no stub pool slot) surface via [_error] as a snackbar.
     */
    fun launchClone(cloneId: Int) {
        viewModelScope.launch {
            val clone = _clones.value.firstOrNull { it.cloneId == cloneId } ?: run {
                _error.value = "Clone $cloneId not found"
                return@launch
            }

            val mainActivity = clone.mainActivity ?: run {
                _error.value = "${clone.displayName} has no launcher Activity — " +
                    "the APK may be a library or split-only package."
                return@launch
            }

            // Resolve the first stub Activity slot for this clone's process group.
            // StubComponentPool.poolByProcessGroup is the Kotlin source of truth that
            // mirrors the manifest-declared stub pool (must be kept in sync by hand —
            // see StubComponentPool).
            val stubClass = StubComponentPool
                .poolByProcessGroup[clone.processGroup]
                ?.get(ComponentType.ACTIVITY)
                ?.firstOrNull()
                ?: run {
                    _error.value = "No stub Activity slot available for process group " +
                        "${clone.processGroup} — check StubComponentPool and AndroidManifest."
                    return@launch
                }

            val intent = Intent().apply {
                setClassName(getApplication<Application>().packageName, stubClass)
                putExtra(EXTRA_GUEST_CLONE_ID, clone.cloneId)
                putExtra(EXTRA_GUEST_PACKAGE_NAME, clone.guestPackageName)
                putExtra(EXTRA_GUEST_CLASS_NAME, mainActivity)
                // FLAG_ACTIVITY_NEW_TASK is required when starting from Application context
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.i(TAG, "Launching clone ${clone.displayName} (id=${clone.cloneId}) " +
                "mainActivity=$mainActivity stub=$stubClass processGroup=${clone.processGroup}")

            try {
                getApplication<Application>().startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "startActivity failed for $stubClass", e)
                _error.value = "Could not start ${clone.displayName}: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun reportError(message: String) {
        _error.value = message
    }

    // ── APK metadata extraction ───────────────────────────────────────────────

    /**
     * Use [PackageManager.getPackageArchiveInfo] to get the real display label and icon
     * from a guest APK without installing it system-wide.
     *
     * For APKS bundles (zip-of-split-APKs): the file itself is not a valid APK so PM
     * will return null; we extract the base.apk to a temp file, query PM on that, then
     * delete the temp. The temp file lives beside the APKS in the same directory (cache).
     *
     * Returns (label, iconPngBytes) — either or both may be null on failure.
     * Failures are non-fatal: we fall back to package name / no icon.
     */
    private fun extractApkMeta(pm: PackageManager, apkFile: File): Pair<String?, ByteArray?> {
        // For APKS bundles, extract base.apk first for PM lookup
        var tempBase: File? = null
        val apkForPm: File = try {
            val result = tryExtractBaseApk(apkFile)
            if (result != null) {
                tempBase = result
                result
            } else {
                apkFile
            }
        } catch (_: Exception) {
            apkFile
        }

        return try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(apkForPm.absolutePath, 0)
            if (info?.applicationInfo == null) return Pair(null, null)

            val appInfo = info.applicationInfo!!
            appInfo.sourceDir = apkForPm.absolutePath
            appInfo.publicSourceDir = apkForPm.absolutePath

            val label = appInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }

            val iconBytes = try {
                val drawable = appInfo.loadIcon(pm)
                val bitmap: Bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    drawable.bitmap
                } else {
                    val size = drawable.intrinsicWidth.coerceIn(48, 192)
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(bmp))
                    bmp
                }
                ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
            } catch (_: Exception) {
                null
            }

            Pair(label, iconBytes)
        } catch (_: Exception) {
            Pair(null, null)
        } finally {
            tempBase?.delete()
        }
    }

    /**
     * If [apksFile] is an APKS bundle (zip containing base.apk), extract base.apk to a
     * temp file and return it. Returns null if the file is a plain APK (not a bundle).
     * Caller is responsible for deleting the returned file.
     */
    private fun tryExtractBaseApk(apksFile: File): File? {
        val zip = try { java.util.zip.ZipFile(apksFile) } catch (_: Exception) { return null }
        return zip.use {
            val baseEntry = it.getEntry("base.apk") ?: return@use null
            if (it.getEntry("AndroidManifest.xml") != null) return@use null // it's a plain APK
            val dest = File(apksFile.parentFile ?: apksFile.absoluteFile.parentFile!!,
                "base_pm_${System.currentTimeMillis()}.apk")
            it.getInputStream(baseEntry).use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            dest
        }
    }

    companion object {
        private const val TAG = "CloneListViewModel"
    }
}
