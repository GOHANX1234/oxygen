package com.oxygens.compat.api34

import com.oxygens.compat.SystemServiceCompat

/**
 * API 34 (Android 14) compat implementation.
 *
 * Same posture as Api31Compat but tracked separately per plan §9 ("a new Android
 * release means adding compat/apiNN/, not touching core logic") — API 33/34 introduced
 * further non-SDK interface list changes and per-app hidden-API opt-out behavior
 * changes that need their own device-verified implementation, not an assumption that
 * API 31's approach still works unchanged.
 */
class Api34Compat : SystemServiceCompat {

    override val id: String = "api34-aosp"

    override fun matches(sdkInt: Int, manufacturer: String): Boolean = sdkInt == 33 || sdkInt == 34

    override fun installPackageManagerHook(vpms: Any): Boolean = false

    override fun installActivityManagerHook(vams: Any): Boolean = false

    override fun requiresNativeHiddenApiExemption(): Boolean = true
}
