package com.oxygens.core.virtual.vpms

import com.oxygens.core.loader.model.ComponentType
import com.oxygens.core.loader.model.ParsedPackage

/**
 * Virtual PackageManagerService (plan §4.1).
 *
 * Answers the subset of PackageManager-shaped queries a guest app or the host UI
 * actually needs, backed by [PackageRegistry] instead of the real PackageManager.
 *
 * This class deliberately does NOT implement `android.content.pm.IPackageManager`
 * directly (that AIDL interface is huge and version-dependent — implementing every
 * method is not worth it for a PoC). Instead it exposes a small, stable Kotlin API
 * that [PackageManagerHookInstaller] adapts into whatever the installed Android
 * version's IPackageManager surface expects, via a per-API `InvocationHandler` in the
 * compat layer. Expand the adapted method set only as guest apps actually call into
 * it — don't try to implement the whole interface up front.
 */
class VirtualPackageManagerService(private val registry: PackageRegistry) {

    fun getPackageInfo(cloneId: Int, packageName: String): ParsedPackage? =
        registry.get(cloneId, packageName)

    fun queryIntentActivities(cloneId: Int, targetClassName: String?): List<ParsedPackage> {
        if (targetClassName == null) return registry.listForClone(cloneId)
        return registry.listForClone(cloneId).filter { pkg ->
            pkg.components.any { it.type == ComponentType.ACTIVITY && it.className == targetClassName }
        }
    }

    fun resolveActivity(cloneId: Int, packageName: String, className: String) =
        registry.get(cloneId, packageName)?.components?.firstOrNull {
            it.type == ComponentType.ACTIVITY && it.className == className
        }

    fun checkPermission(cloneId: Int, packageName: String, permission: String): Boolean =
        registry.get(cloneId, packageName)?.requestedPermissions?.contains(permission) == true

    fun installPackage(cloneId: Int, parsed: ParsedPackage) = registry.register(cloneId, parsed)

    fun uninstallPackage(cloneId: Int, packageName: String) = registry.unregister(cloneId, packageName)

    fun listInstalled(cloneId: Int): List<ParsedPackage> = registry.listForClone(cloneId)
}
