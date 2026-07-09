package com.oxygens.app.stub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
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
 * class then runs the Phase 1 guest delegation pipeline:
 *
 *   1. Builds a per-clone [GuestClassLoaderFactory]/DexClassLoader from the
 *      extracted guest APK stored in VirtualStorage.
 *   2. Loads guest [Resources]/[AssetManager] via the hidden-but-stable
 *      `AssetManager.addAssetPath` reflection path (plan §4.6).
 *   3. Wraps this Activity's Context in [GuestContextWrapper] so the guest's
 *      `getResources()`, `getPackageName()`, `getFilesDir()`, etc. redirect correctly.
 *   4. Instantiates the guest Activity class via the DexClassLoader.
 *   5. Copies the minimal set of Activity-internal fields (mBase, mApplication,
 *      mWindow, mWindowManager, mInstrumentation, mMainThread) from this
 *      already-attached stub onto the guest instance — avoiding the version-dependent
 *      hidden `Activity.attach()` signature entirely.
 *   6. **Critical for rendering**: sets `window.callback = guestActivity` (public API)
 *      so event dispatch goes to the guest, and patches `window.layoutInflater.mContext`
 *      to `guestContext` so that when guest's `setContentView(R.layout.xxx)` runs,
 *      the LayoutInflater resolves layout IDs against guest APK resources rather than
 *      the stub host's resources (which would cause Resources.NotFoundException and
 *      silent finish). System decor resources (package 0x01) remain accessible because
 *      Android's native resource layer always includes the framework package regardless
 *      of which paths were explicitly added to the AssetManager.
 *   7. Calls the guest's `onCreate` via a full class-hierarchy walk (handles the common
 *      case where `onCreate` is declared on `AppCompatActivity`, not the leaf class).
 *   8. Delegates `onResume`/`onPause`/`onDestroy` to the guest instance by reflection.
 *
 * All reflection failures are logged but do not crash Oxygen S — a copy that fails
 * degrades the guest's UI rather than killing the host.
 */
open class StubActivity : Activity() {

    private var cloneId: Int = -1
    private var guestPackageName: String? = null
    private var guestClassName: String? = null

    /** Live guest Activity instance; non-null after [launchGuestActivity] succeeds. */
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
        } catch (t: Throwable) {
            // Rethrow truly fatal VM conditions — attempting to catch and continue
            // after OOM / StackOverflow / ThreadDeath leaves the process in an
            // undefined state that is worse than a fast crash.
            if (t is VirtualMachineError || t is ThreadDeath) throw t

            // For all other Throwable subclasses (including Error subclasses like
            // NoClassDefFoundError / ExceptionInInitializerError / LinkageError),
            // finish gracefully rather than crashing the whole :clone_N process and
            // taking the host Oxygen S UI with it.
            Log.e(TAG, "Failed to launch guest $guestClassName in clone $cloneId", t)
            restoreWindowCallback()
            reportLifecycle(ActivityLifecycleState.DESTROYED)
            finish()
        }
    }

    // ── Guest lifecycle delegation ────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        reportLifecycle(ActivityLifecycleState.RESUMED)
        guestInstance?.let { delegateVoid(it, "onResume") }
    }

    override fun onPause() {
        guestInstance?.let { delegateVoid(it, "onPause") }
        reportLifecycle(ActivityLifecycleState.PAUSED)
        super.onPause()
    }

    override fun onDestroy() {
        guestInstance?.let { delegateVoid(it, "onDestroy") }
        restoreWindowCallback()
        reportLifecycle(ActivityLifecycleState.DESTROYED)
        super.onDestroy()
    }

    // ── Core delegation logic ─────────────────────────────────────────────────

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun launchGuestActivity(savedInstanceState: Bundle?) {
        val pkg = guestPackageName!!
        val cls = guestClassName!!
        val storage = (application as OxygenApplication).virtualStorage

        // ── 1. Locate the extracted guest APK ────────────────────────────────
        // VirtualStorage.extractGuestApk() always writes to apkDir(cloneId, pkg)/base.apk.
        val guestApkPath = File(storage.apkDir(cloneId, pkg), "base.apk").let { f ->
            check(f.exists()) {
                "Extracted APK not found at ${f.absolutePath} for clone $cloneId/$pkg. " +
                    "The clone may need to be reinstalled."
            }
            f.absolutePath
        }
        Log.d(TAG, "Guest APK: $guestApkPath")

        // ── 2. Build a per-clone DexClassLoader ───────────────────────────────
        val optDir = File(codeCacheDir, "dex_opt_$cloneId/${pkg.replace('.', '_')}")
        val nativeLibDir = storage.nativeLibDir(cloneId, pkg)
        val classLoader = GuestClassLoaderFactory(this).create(
            guestApkPath = guestApkPath,
            optimizedDirectory = optDir,
            nativeLibraryDir = nativeLibDir,
        )
        Log.d(TAG, "DexClassLoader ready for $pkg (clone $cloneId)")

        // ── 3. Load guest AssetManager + Resources via reflection ─────────────
        // AssetManager() and addAssetPath() are both @hide. We instantiate via the
        // no-arg constructor (isAccessible) and call addAssetPath to register the
        // guest APK as an asset source. System resources (package 0x01) remain
        // accessible because the Android native resource layer includes the framework
        // package in every AssetManager regardless of what app paths are added.
        val assetManager = createAssetManager(guestApkPath)

        @Suppress("DEPRECATION")
        val guestResources = Resources(
            assetManager,
            resources.displayMetrics,
            resources.configuration,
        )

        // ── 4. Build GuestContextWrapper ──────────────────────────────────────
        val guestContext = GuestContextWrapper(
            base = this,
            guestResources = guestResources,
            guestAssets = assetManager,
            cloneId = cloneId,
            guestPackageName = pkg,
            storage = storage,
        )

        // ── 5. Instantiate the guest Activity class ───────────────────────────
        val guestClass = classLoader.loadClass(cls)
        val guestActivity = guestClass.getDeclaredConstructor().newInstance() as Activity
        Log.d(TAG, "Guest class $cls instantiated")

        // ── 6. Copy essential Activity fields stub → guest ────────────────────
        // Set mBase first so the guest's ContextWrapper is valid before anything
        // calls back into it (e.g. window.callback = guestActivity below triggers
        // no callbacks yet, but AppCompatActivity reads getPackageName() early).
        reflectSet(android.content.ContextWrapper::class.java, "mBase", guestActivity, guestContext)
        reflectSet(Activity::class.java, "mApplication", guestActivity, application)
        // Share our already-attached window and window manager. The guest's
        // setContentView() will render into this real window.
        reflectCopy(Activity::class.java, "mWindow",        guestActivity)
        reflectCopy(Activity::class.java, "mWindowManager", guestActivity)
        // Instrumentation and ActivityThread: same process, safe to share.
        reflectCopy(Activity::class.java, "mInstrumentation", guestActivity)
        reflectCopy(Activity::class.java, "mMainThread",      guestActivity)
        // Configuration for the running process.
        reflectCopy(Activity::class.java, "mCurrentConfig", guestActivity)

        // ── 7. Wire window callback to guest ──────────────────────────────────
        // window.setCallback() is a public API. Setting it to the guest Activity
        // before guest's onCreate() ensures:
        //   a) AppCompatActivity.onCreate reads mWindow.getCallback() → guestActivity
        //      and stores it as the "original" callback (correct).
        //   b) Key/touch events dispatched by the framework go to the guest, not stub.
        // mBase is already set above so callback calls into the guest Context are safe.
        window.callback = guestActivity

        // ── 8. Patch the window's LayoutInflater to use guest resources ────────
        // ROOT CAUSE OF THE CRASH: without this patch, when the guest calls
        // setContentView(R.layout.activity_main), PhoneWindow.setContentView()
        // uses its internal mLayoutInflater (whose mContext = stub Activity) to
        // inflate the layout. The stub doesn't have the guest's resources, so
        // LayoutInflater.inflate() throws Resources.NotFoundException.
        //
        // We patch mContext *inside* the existing LayoutInflater object (returned by
        // the public window.layoutInflater getter) rather than replacing the inflater
        // itself. This is safe because:
        //   • System decor layouts (0x01xxxxxx IDs) still resolve: Android's native
        //     resource layer always includes the framework package in every app's
        //     AssetManager.
        //   • Guest layouts (0x7fxxxxxx IDs) now resolve against guestResources. ✓
        //   • The same inflater instance is returned by getSystemService(LAYOUT_INFLATER)
        //     via the stub Activity's Activity.getSystemService(), which GuestContextWrapper
        //     inherits — so LayoutInflater.from(guestContext) also gets the patched one.
        patchInflaterContext(guestContext)

        // ── 9. Call guest's onCreate via full class-hierarchy walk ────────────
        // getDeclaredMethod() only inspects the immediate class. Guest apps commonly
        // declare onCreate on a base class (e.g. AppCompatActivity), so we walk up
        // until we find the first class that declares onCreate(Bundle).
        val onCreateMethod = findMethodOnHierarchy(guestClass, "onCreate", Bundle::class.java)
            ?: error("No onCreate(Bundle) found on $cls or any of its superclasses")
        onCreateMethod.invoke(guestActivity, savedInstanceState)
        Log.i(TAG, "Guest $cls onCreate() completed for clone $cloneId ✓")

        guestInstance = guestActivity
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Create a guest [AssetManager] with [guestApkPath] added. Uses reflection to
     * call the hidden no-arg constructor (and addAssetPath) with two fallbacks for
     * OEM variants that moved the constructor.
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun createAssetManager(guestApkPath: String): AssetManager {
        val am: AssetManager = try {
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (e: Exception) {
            Log.w(TAG, "AssetManager no-arg ctor via getDeclaredConstructor failed (${e.message}); " +
                "falling back to newInstance()")
            @Suppress("DEPRECATION")
            try {
                AssetManager::class.java.newInstance()
            } catch (e2: Exception) {
                throw IllegalStateException(
                    "Could not instantiate AssetManager via reflection — " +
                        "hidden-API access is blocked on this build. " +
                        "Native hidden-API exemption (plan §4.6) is required.", e2)
            }
        }

        try {
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .also { it.isAccessible = true }
            val cookie = addAssetPath.invoke(am, guestApkPath) as? Int ?: 0
            check(cookie != 0) {
                "addAssetPath returned 0 for $guestApkPath — the APK file may be corrupt or missing."
            }
            Log.d(TAG, "addAssetPath cookie=$cookie for $guestApkPath")
        } catch (e: Exception) {
            throw IllegalStateException(
                "addAssetPath reflection failed — guest resources will not be available.", e)
        }

        return am
    }

    /**
     * Replace the window's LayoutInflater with one that resolves layouts against
     * [guestContext]'s resources.
     *
     * **Why not patch `LayoutInflater.mContext` directly?**
     * `mContext` is declared `protected final` in AOSP's `LayoutInflater`. Mutating a
     * `final` field via reflection after construction is unreliable: ART on Android 12+
     * may silently ignore the write, and some OEM builds enforce the restriction at the
     * native layer. Any approach that relies on this is fragile across the API 28-35
     * range we target.
     *
     * **The reliable alternative:**
     * 1. `LayoutInflater.cloneInContext(guestContext)` — public API, creates a new
     *    inflater whose `mContext` is correctly set to `guestContext` *in the
     *    constructor* (no final-field mutation needed).
     * 2. Replace `PhoneWindow.mLayoutInflater` with the cloned inflater via
     *    reflection. This field is `private` but **NOT** `final` in PhoneWindow across
     *    API 28-35, so `isAccessible = true` + `set()` is reliable.
     *
     * After this call, when the guest's `setContentView(R.layout.activity_main)` runs,
     * `PhoneWindow.setContentView()` calls `mLayoutInflater.inflate(resId, ...)` which
     * uses `guestContext.getResources()` (= guest APK resources) to resolve the ID. ✓
     * System decor layouts (0x01xxxxxx IDs, used by installDecor) also continue to
     * resolve because Android's native resource layer always includes the framework
     * package in every app's AssetManager regardless of which APK paths were added.
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun patchInflaterContext(guestContext: GuestContextWrapper) {
        try {
            // Step 1: clone the existing inflater with the guest context (public API).
            val guestInflater: LayoutInflater = window.layoutInflater.cloneInContext(guestContext)

            // Step 2: replace mLayoutInflater by walking window.javaClass up its
            // superclass chain until the declaring class is found. This is more
            // future-proof than hardcoding "com.android.internal.policy.PhoneWindow"
            // which could move across OEM builds or Android versions.
            val windowInstance = window
            var klass: Class<*>? = windowInstance.javaClass
            var layoutInflaterField: java.lang.reflect.Field? = null
            while (klass != null && layoutInflaterField == null) {
                layoutInflaterField = try {
                    klass.getDeclaredField("mLayoutInflater").also { it.isAccessible = true }
                } catch (_: NoSuchFieldException) { null }
                klass = klass.superclass
            }

            if (layoutInflaterField != null) {
                layoutInflaterField.set(windowInstance, guestInflater)
                Log.d(TAG, "mLayoutInflater (on ${layoutInflaterField.declaringClass.simpleName}) " +
                    "replaced with guest-context inflater")
            } else {
                Log.e(TAG, "CRITICAL: mLayoutInflater field not found in window class hierarchy — " +
                    "guest layouts will fail to inflate")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Could not replace mLayoutInflater — guest layouts will " +
                "fail to inflate. ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Restore window callback to the stub Activity so framework cleanup goes to the right place. */
    private fun restoreWindowCallback() {
        try { window.callback = this } catch (_: Exception) {}
    }

    /**
     * Walk [startClass] and its superclasses until a class that declares
     * [methodName] with the given [paramTypes] is found. Returns null if not found.
     * This handles the common case where the guest's leaf class (e.g.
     * `MainActivity extends AppCompatActivity`) doesn't itself declare `onCreate`
     * — `getDeclaredMethod` would throw, but the method exists on `AppCompatActivity`.
     */
    private fun findMethodOnHierarchy(
        startClass: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>,
    ): java.lang.reflect.Method? {
        var klass: Class<*>? = startClass
        while (klass != null && klass != Any::class.java) {
            try {
                return klass.getDeclaredMethod(methodName, *paramTypes)
                    .also { it.isAccessible = true }
            } catch (_: NoSuchMethodException) {}
            klass = klass.superclass
        }
        return null
    }

    /**
     * Set [fieldName] (declared on [declaringClass]) on [target] to [value].
     * Non-fatal: logs a warning if the field is not found or access is denied.
     */
    private fun reflectSet(declaringClass: Class<*>, fieldName: String, target: Any, value: Any?) {
        try {
            declaringClass.getDeclaredField(fieldName).also { it.isAccessible = true }.set(target, value)
        } catch (e: Exception) {
            Log.w(TAG, "reflectSet $fieldName on ${declaringClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Copy [fieldName] (declared on [declaringClass]) from this stub Activity
     * to [target]. Non-fatal: logs a warning if the field is not found.
     */
    private fun reflectCopy(declaringClass: Class<*>, fieldName: String, target: Any) {
        try {
            val f = declaringClass.getDeclaredField(fieldName).also { it.isAccessible = true }
            f.set(target, f.get(this))
        } catch (e: Exception) {
            Log.w(TAG, "reflectCopy $fieldName from ${declaringClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Invoke a no-arg method by walking the class hierarchy of [target].
     * Used for lifecycle callbacks (onResume / onPause / onDestroy) so they are
     * delegated even when declared on a superclass of the guest.
     */
    private fun delegateVoid(target: Activity, methodName: String) {
        try {
            val method = findMethodOnHierarchy(target.javaClass, methodName)
                ?: return Unit.also {
                    Log.w(TAG, "delegateVoid: $methodName not found on ${target.javaClass.name}")
                }
            method.invoke(target)
        } catch (t: Throwable) {
            // Mirror the fatal-rethrow policy from the top-level launch catch so that
            // OOM / ThreadDeath during lifecycle callbacks also propagates correctly.
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            Log.w(TAG, "delegateVoid $methodName on ${target.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── VAMS lifecycle reporting ──────────────────────────────────────────────

    private fun reportLifecycle(state: ActivityLifecycleState) {
        val className = guestClassName ?: return
        if (cloneId == -1) return
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
