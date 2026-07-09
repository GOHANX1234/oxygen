package com.oxygens.core.virtual.vpms

import com.oxygens.compat.SystemServiceCompat

/**
 * Java-level substitution point for `IPackageManager` (plan §4.6, hook type 1).
 *
 * The framework caches the real IPackageManager Binder proxy in a static/singleton
 * field (historically `ActivityThread.sPackageManager`; the exact field/accessor
 * shifts across API levels, which is why the actual reflection lives behind
 * [SystemServiceCompat] rather than here). This class is the single call site the
 * rest of the app uses to install the substitution — it must run before the guest's
 * `Application.onCreate()` in the clone process (see OxygenApplication).
 *
 * IMPORTANT: the exact singleton field name/holder and whether reflection alone is
 * sufficient (vs. needing the JNIEnvExt hidden-API exemption from native-hook) is
 * genuinely different per API level and sometimes per OEM. Do not hardcode a single
 * strategy — always go through [SystemServiceCompat.installPackageManagerHook], which
 * is expected to be filled in per api2X module after a real-device research spike.
 * Treat a thrown/caught failure here as a hard signal to fall back to graceful
 * "hook-incompatible on this OS version" reporting (plan §9), not a silent no-op.
 */
class PackageManagerHookInstaller(
    private val compat: SystemServiceCompat,
    private val vpms: VirtualPackageManagerService,
) {

    /** @throws HookInstallationException if the current OS version / OEM combination
     *  is not supported by any registered compat implementation. */
    fun install() {
        val installed = compat.installPackageManagerHook(vpms)
        if (!installed) {
            throw HookInstallationException(
                "IPackageManager substitution failed for SDK ${android.os.Build.VERSION.SDK_INT} " +
                    "on ${android.os.Build.MANUFACTURER}. This must be surfaced to the user as " +
                    "'hook-incompatible on this OS version', not swallowed (plan §9)."
            )
        }
    }
}

class HookInstallationException(message: String) : Exception(message)
