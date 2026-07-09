package com.oxygens.compat.api31

import com.oxygens.compat.SystemServiceCompat

/**
 * API 31-32 (Android 12/12L) compat implementation.
 *
 * This range tightened the non-SDK interface (hidden API) enforcement lists compared
 * to 28-30 — some fields reflection could reach directly on Pie/Q/R move to the
 * blocklist here on several OEM skins. Per plan §4.6, this is exactly the boundary
 * where the native JNIEnvExt hidden-API exemption becomes the more durable path
 * rather than an optional hardening step — [requiresNativeHiddenApiExemption] returns
 * true accordingly. The actual struct-offset/ART-internal work behind that exemption
 * lives in native-hook and MUST be validated per real device before this class can be
 * marked as verified for a given OEM build.
 */
class Api31Compat : SystemServiceCompat {

    override val id: String = "api31-32-aosp"

    override fun matches(sdkInt: Int, manufacturer: String): Boolean = sdkInt in 31..32

    override fun installPackageManagerHook(vpms: Any): Boolean {
        // TODO(research-spike): route through native-hook's hidden-API exemption
        // (NativeHookBridge) before attempting the ActivityThread.sPackageManager
        // reflection used on API 28-30 — plain reflection is not expected to be
        // reliable here across OEM skins. Not implemented: fail loud (return false),
        // do not fall back to a fake success.
        return false
    }

    override fun installActivityManagerHook(vams: Any): Boolean = false

    override fun requiresNativeHiddenApiExemption(): Boolean = true
}
