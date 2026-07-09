package com.oxygens.compat

/**
 * The single interface every OS-version-specific hack lives behind (plan §5/§9).
 * A new Android release means adding `compat/apiNN/ApiNNCompat.kt`, never touching
 * core-virtual/core-loader/core-storage logic.
 *
 * `vpms`/`vams` are passed as `Any` (rather than a strongly-typed core-virtual class)
 * so this module never needs to depend on core-virtual, avoiding a module cycle —
 * each compat implementation casts to the concrete Kotlin type it expects at the one
 * call site that needs it.
 */
interface SystemServiceCompat {

    /** Human-readable id for logs/diagnostics, e.g. "api34-aosp", "api34-oneui". */
    val id: String

    /** True if this compat implementation claims to support the running device. */
    fun matches(sdkInt: Int, manufacturer: String): Boolean

    /**
     * Installs the IPackageManager Java-level substitution (plan §4.6 hook type 1).
     * @return true if installation succeeded, false if this API level/OEM combination
     *   turned out not to be supported after all (caller must treat false as a hard
     *   "hook-incompatible" signal, not retry silently).
     */
    fun installPackageManagerHook(vpms: Any): Boolean

    /**
     * Installs the IActivityManager Java-level substitution.
     */
    fun installActivityManagerHook(vams: Any): Boolean

    /**
     * Whether hidden-API access for this compat implementation's target level is
     * expected to require the native JNIEnvExt exemption path (native-hook module)
     * rather than plain reflection. Plan §4.6: "the more durable of the two
     * workarounds" — reflection alone is expected to degrade on newer API levels.
     */
    fun requiresNativeHiddenApiExemption(): Boolean
}

/**
 * Picks the right [SystemServiceCompat] for the running device (plan §9: key on
 * `Build.VERSION.SDK_INT` and, where OEM divergence is known to matter, on
 * `Build.MANUFACTURER`).
 *
 * Registered implementations are intentionally listed newest-first so a closer/more
 * specific match (e.g. a future OEM-specific override) can be added ahead of the
 * generic AOSP one for the same SDK level.
 */
class CompatProvider(private val implementations: List<SystemServiceCompat>) {

    class NoCompatibleImplementationException(message: String) : Exception(message)

    fun resolve(sdkInt: Int, manufacturer: String): SystemServiceCompat =
        implementations.firstOrNull { it.matches(sdkInt, manufacturer) }
            ?: throw NoCompatibleImplementationException(
                "No compat implementation registered for SDK $sdkInt / $manufacturer. " +
                    "This must be reported to the user as an unsupported OS version " +
                    "(plan §9), not silently ignored."
            )
}
