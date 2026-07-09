package com.oxygens.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.oxygens.compat.CompatProvider
import com.oxygens.compat.api28.Api28Compat
import com.oxygens.compat.api31.Api31Compat
import com.oxygens.compat.api34.Api34Compat
import com.oxygens.compat.api35.Api35Compat
import com.oxygens.core.virtual.VirtualCore
import com.oxygens.core.storage.VirtualStorage
import com.oxygens.core.loader.GuestApkParser
import com.oxygens.registry.CloneDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Process-attach entry point (plan §4.6 hook type 1).
 *
 * `attachBaseContext` runs before `onCreate` and, critically, before any guest
 * `Application.onCreate()` in a clone process — this is where VirtualCore.bootstrap
 * must run so the Java-level PackageManager/ActivityManager substitution is in place
 * before guest code gets a chance to call into the real services.
 *
 * This same Application class runs in BOTH the host's main process and every
 * ":clone_N" process (Android instantiates the manifest-declared Application class in
 * every declared process). [android.os.Process.myProcessName] (API 28+) tells us
 * which one we're in — bootstrap only needs to happen in clone processes; the host UI
 * process never runs guest code directly.
 */
class OxygenApplication : Application() {

    lateinit var virtualStorage: VirtualStorage
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        virtualStorage = VirtualStorage(base)

        val processName = currentProcessName(base)
        if (processName?.contains(":clone_") != true) {
            // Host UI process — no guest code runs here, nothing to bootstrap.
            return
        }

        val compatProvider = CompatProvider(
            listOf(Api35Compat(), Api34Compat(), Api31Compat(), Api28Compat())
        )
        val compat = compatProvider.resolve(Build.VERSION.SDK_INT, Build.MANUFACTURER)

        when (val result = VirtualCore.bootstrap(compat)) {
            is VirtualCore.BootstrapResult.Success -> {
                rehydrateClone(base, processName)
            }
            is VirtualCore.BootstrapResult.HookDegraded -> {
                // Hook installation failed (e.g. OEM ROM moved the reflection target field).
                // Per the fix plan: degrade gracefully — log prominently but do NOT crash.
                // The DexClassLoader → stub delegation pipeline still works; only
                // PM-level isolation (VPMS substituting the real IPackageManager) is lost.
                // Phase 3+ should surface this state to the host UI (plan §9).
                Log.w(
                    TAG,
                    "Oxygen S PM hook layer degraded on this device " +
                        "(compat=${compat.id}): ${result.reason}. " +
                        "Guest isolation is reduced — continuing without PM hook substitution.",
                )
                rehydrateClone(base, processName)
            }
        }
    }

    private fun currentProcessName(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            android.os.Process.myProcessName()
        } else {
            null
        }

    /**
     * Each clone process starts with nothing but its own process name — VPMS is an
     * in-process fake with no cross-process shared memory, so every ":clone_N"
     * process must independently reconstruct the registry entries it needs from
     * durable state (clone-registry-db + the guest APK on disk), rather than assuming
     * anything the host UI process built up in its own memory is visible here.
     *
     * This runs a one-time blocking DB read + APK re-parse on process attach, before
     * any guest code runs. That's an intentional, bounded startup cost (consistent
     * with how VirtualApp/DroidPlugin-style hosts set up a clone process, plan §8) —
     * not a background/async step, since guest code must never observe VPMS in a
     * half-populated state.
     */
    private fun rehydrateClone(context: Context, processName: String) = runBlocking {
        val processGroup = processName.substringAfter(context.packageName, missingDelimiterValue = processName)
        val entity = CloneDatabase.getInstance(context).cloneDao().getByProcessGroup(processGroup)
        if (entity == null) {
            throw IllegalStateException(
                "Clone process attached for process group '$processGroup' but no clone " +
                    "is registered for it in clone-registry-db. This should not happen " +
                    "outside of manual testing — a stub component's process group must " +
                    "always be allocated to a clone before that process can be started."
            )
        }

        val parsed = GuestApkParser().parse(File(entity.guestApkPath))
        VirtualCore.vpms.installPackage(entity.cloneId, parsed)
    }

    companion object {
        private const val TAG = "OxygenApplication"
    }
}
