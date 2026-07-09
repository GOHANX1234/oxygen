package com.oxygens.compat.api28

import com.oxygens.compat.SystemServiceCompat
import java.lang.reflect.Field

/**
 * API 28-30 (Pie/Q/R) compat implementation.
 *
 * At this range, `ActivityManager.getService()` and `ActivityThread.getPackageManager()`
 * (backed by the `ActivityThread.sPackageManager` field) are the documented AOSP
 * singleton access points. Both are on the light-greylist/greylist tiers for this
 * range on AOSP, so plain reflection is expected to work WITHOUT the native
 * JNIEnvExt exemption — but this must still be confirmed on a real device per plan
 * §4.6 before relying on it; do not treat the field names below as verified for every
 * OEM build in this range.
 */
class Api28Compat : SystemServiceCompat {

    override val id: String = "api28-30-aosp"

    override fun matches(sdkInt: Int, manufacturer: String): Boolean = sdkInt in 28..30

    override fun installPackageManagerHook(vpms: Any): Boolean {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            // TODO(research-spike): once the IPackageManager proxy below exists, this
            // needs `activityThreadClass.getMethod("currentActivityThread").invoke(null)`
            // as the target instance to set sPackageManagerField on.
            val sPackageManagerField: Field = activityThreadClass.getDeclaredField("sPackageManager")
            sPackageManagerField.isAccessible = true

            // TODO(research-spike): sPackageManagerField expects an IPackageManager
            // Binder interface instance, not a raw VirtualPackageManagerService. This
            // needs a java.lang.reflect.Proxy implementing IPackageManager.Stub that
            // delegates the handful of methods VPMS actually answers (plan §4.1) to
            // `vpms`, and returns default/unsupported for the rest. Wiring that proxy
            // is the concrete Phase 1 task this class is a placeholder for — verify on
            // a real API 28-30 device before considering this "done".
            false
        } catch (e: ReflectiveOperationException) {
            false
        }
    }

    override fun installActivityManagerHook(vams: Any): Boolean {
        return try {
            val activityManagerClass = Class.forName("android.app.ActivityManager")
            activityManagerClass.getMethod("getService")
            // TODO(research-spike): same shape of work as installPackageManagerHook,
            // but for IActivityManager. See class doc.
            false
        } catch (e: ReflectiveOperationException) {
            false
        }
    }

    override fun requiresNativeHiddenApiExemption(): Boolean = false
}
