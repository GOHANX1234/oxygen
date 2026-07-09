package com.oxygens.app.stub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import com.oxygens.app.OxygenApplication
import com.oxygens.core.loader.GuestClassLoaderFactory
import com.oxygens.core.virtual.VirtualCore
import com.oxygens.core.virtual.vams.ActivityLifecycleState
import com.oxygens.core.virtual.vams.VirtualActivityManagerService.Companion.EXTRA_GUEST_CLASS_NAME
import com.oxygens.core.virtual.vams.VirtualActivityManagerService.Companion.EXTRA_GUEST_CLONE_ID
import com.oxygens.core.virtual.vams.VirtualActivityManagerService.Companion.EXTRA_GUEST_PACKAGE_NAME
import com.oxygens.core.virtual.vwms.GuestContextWrapper
import java.io.File

/**
 * Base for the manifest-declared stub Activity pool (plan §4.2/§4.3).
 *
 * Android only launches Activity classes declared in Oxygen S's own manifest, so
 * StubActivityN is what Android instantiates and gives a real Window to. This base
 * class then:
 *   1. Builds a per-clone [GuestClassLoaderFactory]/[DexClassLoader] from the
 *      extracted guest APK path in [VirtualStorage].
 *   2. Loads guest [Resources]/[AssetManager] via the hidden-but-stable
 *      `AssetManager.addAssetPath` reflection path (plan §4.6).
 *   3. Wraps this Activity's [Context] in a [GuestContextWrapper] so the guest's
 *      `getResources()`, `getPackageName()`, `getFilesDir()`, etc. all redirect
 *      correctly.
 *   4. Instantiates the guest Activity class and copies the minimal set of
 *      Activity-internal fields (window, instrumentation, application, main thread)
 *      from this already-attached stub onto the guest instance — avoiding the
 *      version-dependent hidden `Activity.attach()` signature entirely.
 *   5. Calls the guest's `onCreate` (and later `onResume`/`onPause`/`onDestroy`) via
 *      reflection, so the guest's UI renders inside our real window.
 *
 * Field copies use `isAccessible = true`; this works on API 28-35 in non-debuggable
 * builds if the app is whitelisted (or native-hook exemption is active — plan §4.6).
 * Failures are caught and logged; a failed copy degrades the guest's UI rather than
 * crashing Oxygen S itself.
 */
open class StubActivity : Activity() {

    private var cloneId: Int = -1
    private var guestPackageName: String? = null
    private var guestClassName: String? = null

    /** The live guest Activity instance; non-null after [launchGuestActivity] succeeds. */
    private var guestInstance: Activity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cloneId = intent.getIntExtra(EXTRA_GUEST_CLONE_ID, -1)
        guestPackageName = intent.getStringExtra(EXTRA_GUEST_PACKAGE_NAME)
        guestClassName = intent.getStringExtra(EXTRA_GUEST_CLASS_NAME)

        if (cloneId == -1 || guestPackageName == null || guestClassName == null) {
            Log.e(TAG, "StubActivity started without required extras — finishing. " +
                "cloneId=$cloneId pkg=$guestPackageName cls=$guestClassName")
            finish()
            return
        }

        try {
            launchGuestActivity(savedInstanceState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch guest $guestClassName in clone $cloneId", e)
            // Report destroyed so VAMS releases the stub slot
            reportLifecycle(ActivityLifecycleState.DESTROYED)
            finish()
        }
    }

    // ── Guest lifecycle delegation ────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        reportLifecycle(ActivityLifecycleState.RESUMED)
        guestInstance?.let { delegateLifecycle(it, "onResume") }
    }

    override fun onPause() {
        guestInstance?.let { delegateLifecycle(it, "onPause") }
        reportLifecycle(ActivityLifecycleState.PAUSED)
        super.onPause()
    }

    override fun onDestroy() {
        guestInstance?.let { delegateLifecycle(it, "onDestroy") }
        reportLifecycle(ActivityLifecycleState.DESTROYED)
        super.onDestroy()
    }

    // ── Core delegation logic ─────────────────────────────────────────────────

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun launchGuestActivity(savedInstanceState: Bundle?) {
        val pkg = guestPackageName!!
        val cls = guestClassName!!
        val storage = (application as OxygenApplication).virtualStorage

        // ── Step 1: locate the extracted APK ─────────────────────────────────
        // VirtualStorage.extractGuestApk() always writes to apkDir(cloneId, pkg)/base.apk.
        val guestApkPath = File(storage.apkDir(cloneId, pkg), "base.apk").absolutePath

        // ── Step 2: build a per-clone DexClassLoader ──────────────────────────
        // optimizedDirectory lives under codeCacheDir (per-app, survives upgrades).
        val optDir = File(codeCacheDir, "dex_opt_$cloneId/${pkg.replace('.', '_')}")
        val nativeLibDir = storage.nativeLibDir(cloneId, pkg)
        val classLoader = GuestClassLoaderFactory(this).create(
            guestApkPath = guestApkPath,
            optimizedDirectory = optDir,
            nativeLibraryDir = nativeLibDir,
        )
        Log.d(TAG, "DexClassLoader ready for $pkg (clone $cloneId) apk=$guestApkPath")

        // ── Step 3: load guest AssetManager + Resources via reflection ────────
        // AssetManager() constructor and addAssetPath() are both @hide.
        // We instantiate via the no-arg constructor (isAccessible) and then call
        // addAssetPath to register the guest APK as an asset source.
        val assetManager: AssetManager = try {
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (e: Exception) {
            // Fallback: try newInstance() directly (works on some OEM builds)
            @Suppress("DEPRECATION")
            AssetManager::class.java.newInstance()
        }

        try {
            val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            addAssetPath.invoke(assetManager, guestApkPath)
            Log.d(TAG, "Guest AssetManager loaded for $guestApkPath")
        } catch (e: Exception) {
            Log.w(TAG, "addAssetPath reflection failed — guest resources may be missing: ${e.message}")
        }

        @Suppress("DEPRECATION")
        val guestResources = Resources(
            assetManager,
            resources.displayMetrics,
            resources.configuration,
        )

        // ── Step 4: build GuestContextWrapper ────────────────────────────────
        val guestContext = GuestContextWrapper(
            base = this,
            guestResources = guestResources,
            guestAssets = assetManager,
            cloneId = cloneId,
            guestPackageName = pkg,
            storage = storage,
        )

        // ── Step 5: instantiate guest Activity class ──────────────────────────
        val guestClass = classLoader.loadClass(cls)
        Log.d(TAG, "Loaded guest class $cls from clone $cloneId")
        val guestActivity = guestClass.getDeclaredConstructor().newInstance() as Activity

        // ── Step 6: attach guest to our window via field reflection ───────────
        // We set the ContextWrapper base to our GuestContextWrapper so that
        // getResources()/getPackageName()/getFilesDir() all redirect correctly.
        reflectSetField(android.content.ContextWrapper::class.java, "mBase",
            guestActivity, guestContext)

        // Share our already-attached Application, Window, WindowManager,
        // Instrumentation, and ActivityThread with the guest. These are safe to
        // share because:
        //   • mWindow   — the guest's setContentView() renders into our real window.
        //   • mApplication — OxygenApplication; guest code using getApplicationContext()
        //                    gets our app (acceptable for Phase 1).
        //   • mInstrumentation / mMainThread — same process, same thread model.
        // mToken is intentionally NOT copied: it is the Binder handle AMS uses to
        // identify *this* stub Activity. If the guest had the same token, finishing
        // it via reflection could double-finish the stub window.
        reflectCopyField(Activity::class.java, "mApplication", guestActivity)
        reflectCopyField(Activity::class.java, "mWindow", guestActivity)
        reflectCopyField(Activity::class.java, "mWindowManager", guestActivity)
        reflectCopyField(Activity::class.java, "mInstrumentation", guestActivity)
        reflectCopyField(Activity::class.java, "mMainThread", guestActivity)
        // mCurrentConfig lets the guest respond to configuration changes correctly
        reflectCopyField(Activity::class.java, "mCurrentConfig", guestActivity)

        // ── Step 7: call guest onCreate() via reflection ──────────────────────
        // We find onCreate() on the guest's own class first; if not overridden,
        // walk up to Activity.onCreate(Bundle) in the framework.
        val onCreateMethod = try {
            guestClass.getDeclaredMethod("onCreate", Bundle::class.java)
                .also { it.isAccessible = true }
        } catch (_: NoSuchMethodException) {
            Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
                .also { it.isAccessible = true }
        }
        onCreateMethod.invoke(guestActivity, savedInstanceState)
        Log.i(TAG, "Guest $cls onCreate() completed for clone $cloneId")

        guestInstance = guestActivity
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /**
     * Copy field [fieldName] (declared on [declaringClass]) from this stub Activity
     * into [target]. Logs a warning on failure but does not throw.
     */
    private fun reflectCopyField(declaringClass: Class<*>, fieldName: String, target: Any) {
        try {
            val f = declaringClass.getDeclaredField(fieldName)
            f.isAccessible = true
            f.set(target, f.get(this))
        } catch (e: Exception) {
            Log.w(TAG, "reflectCopyField $fieldName from ${declaringClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Set field [fieldName] (declared on [declaringClass]) on [target] to [value].
     * Logs a warning on failure but does not throw.
     */
    private fun reflectSetField(declaringClass: Class<*>, fieldName: String, target: Any, value: Any?) {
        try {
            val f = declaringClass.getDeclaredField(fieldName)
            f.isAccessible = true
            f.set(target, value)
        } catch (e: Exception) {
            Log.w(TAG, "reflectSetField $fieldName on ${declaringClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Call a no-arg public/protected method [methodName] on [target] by walking its
     * class hierarchy. Used for lifecycle callbacks (onResume/onPause/onDestroy).
     */
    private fun delegateLifecycle(target: Activity, methodName: String) {
        try {
            var klass: Class<*>? = target.javaClass
            var method: java.lang.reflect.Method? = null
            while (klass != null && method == null) {
                method = try {
                    klass.getDeclaredMethod(methodName)
                } catch (_: NoSuchMethodException) {
                    null
                }
                klass = klass.superclass
            }
            method?.also {
                it.isAccessible = true
                it.invoke(target)
            } ?: Log.w(TAG, "delegateLifecycle: $methodName not found on ${target.javaClass.name}")
        } catch (e: Exception) {
            Log.w(TAG, "delegateLifecycle $methodName on ${target.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── VAMS lifecycle reporting ──────────────────────────────────────────────

    private fun reportLifecycle(state: ActivityLifecycleState) {
        val className = guestClassName ?: return
        if (cloneId == -1) return
        // Guards against calling into VirtualCore.vams before bootstrap ran (e.g. if
        // a stub Activity is somehow started in the host process by mistake) — VAMS is
        // a `lateinit var`, so an uninitialized access throws rather than NPEs.
        runCatching { VirtualCore.vams }.getOrNull()?.reportLifecycle(cloneId, className, state)
    }

    companion object {
        private const val TAG = "StubActivity"
    }
}

class StubActivity0 : StubActivity()
class StubActivity1 : StubActivity()
class StubActivity2 : StubActivity()
class StubActivity3 : StubActivity()
