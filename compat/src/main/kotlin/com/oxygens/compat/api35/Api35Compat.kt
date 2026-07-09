package com.oxygens.compat.api35

import com.oxygens.compat.SystemServiceCompat

/**
 * API 35 (Android 15) compat implementation — current `compileSdk`/`targetSdk` for
 * this project (see app/build.gradle.kts). Track this module actively: this is the
 * version new development and the Phase 1 PoC device should target first.
 */
class Api35Compat : SystemServiceCompat {

    override val id: String = "api35-aosp"

    override fun matches(sdkInt: Int, manufacturer: String): Boolean = sdkInt >= 35

    override fun installPackageManagerHook(vpms: Any): Boolean = false

    override fun installActivityManagerHook(vams: Any): Boolean = false

    override fun requiresNativeHiddenApiExemption(): Boolean = true
}
