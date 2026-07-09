package com.oxygens.core.virtual

import com.oxygens.compat.SystemServiceCompat
import com.oxygens.core.virtual.vams.StubComponentAllocator
import com.oxygens.core.virtual.vams.VirtualActivityManagerService
import com.oxygens.core.virtual.vpms.HookInstallationException
import com.oxygens.core.virtual.vpms.PackageManagerHookInstaller
import com.oxygens.core.virtual.vpms.PackageRegistry
import com.oxygens.core.virtual.vpms.VirtualPackageManagerService

/**
 * Process-attach bootstrap for the virtual services (plan §4.6 hook type 1: "replacing
 * a cached singleton field, not patching machine code").
 *
 * [bootstrap] must run as early as possible in a clone process's lifetime — ideally
 * from `Application.attachBaseContext`, before any guest code runs and before the
 * guest's own `Application.onCreate()` — so that by the time the guest calls into
 * PackageManager/ActivityManager, our substitution is already in place.
 */
object VirtualCore {

    lateinit var packageRegistry: PackageRegistry
        private set
    lateinit var vpms: VirtualPackageManagerService
        private set
    lateinit var stubAllocator: StubComponentAllocator
        private set
    lateinit var vams: VirtualActivityManagerService
        private set

    private var bootstrapped = false

    sealed class BootstrapResult {
        data object Success : BootstrapResult()
        /**
         * Hook installation failed but the process can continue — guest code will run
         * against the real PackageManager/ActivityManager, meaning isolation is reduced,
         * but the launch pipeline (DexClassLoader → guest resources → stub delegation)
         * still works. OxygenApplication degrades gracefully rather than crashing.
         *
         * This is distinct from a truly unrecoverable failure (e.g. no stub pool) and
         * is expected on OEM ROMs where the reflection target fields have moved (plan §9).
         */
        data class HookDegraded(val reason: String) : BootstrapResult()
    }

    @Synchronized
    fun bootstrap(compat: SystemServiceCompat): BootstrapResult {
        if (bootstrapped) return BootstrapResult.Success

        packageRegistry = PackageRegistry()
        vpms = VirtualPackageManagerService(packageRegistry)
        stubAllocator = StubComponentAllocator()
        vams = VirtualActivityManagerService(vpms, stubAllocator)

        return try {
            PackageManagerHookInstaller(compat, vpms).install()
            bootstrapped = true
            BootstrapResult.Success
        } catch (e: HookInstallationException) {
            // Hook failed — mark bootstrapped so we don't retry every call, and return
            // HookDegraded so the caller can continue without isolation rather than crashing.
            // The virtual services (vpms/vams/stubAllocator) are fully initialised above
            // and remain usable for in-process package tracking even without the PM hook.
            bootstrapped = true
            BootstrapResult.HookDegraded(e.message ?: "unknown hook failure")
        }
    }
}
