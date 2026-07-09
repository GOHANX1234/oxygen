package com.oxygens.core.virtual.vpms

import com.oxygens.core.loader.model.ParsedPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of "installed" guest packages, keyed by (cloneId, packageName).
 * Populated by CloneManager from core-loader.GuestApkParser output. This is the data
 * VPMS answers PackageManager-shaped queries from — it never touches the real
 * PackageManager/PackageInstaller (plan §4.1).
 */
class PackageRegistry {

    private data class Key(val cloneId: Int, val packageName: String)

    private val packages = ConcurrentHashMap<Key, ParsedPackage>()

    fun register(cloneId: Int, parsed: ParsedPackage) {
        packages[Key(cloneId, parsed.packageName)] = parsed
    }

    fun unregister(cloneId: Int, packageName: String) {
        packages.remove(Key(cloneId, packageName))
    }

    fun get(cloneId: Int, packageName: String): ParsedPackage? =
        packages[Key(cloneId, packageName)]

    fun listForClone(cloneId: Int): List<ParsedPackage> =
        packages.entries.filter { it.key.cloneId == cloneId }.map { it.value }

    fun all(): List<Pair<Int, ParsedPackage>> =
        packages.entries.map { it.key.cloneId to it.value }
}
