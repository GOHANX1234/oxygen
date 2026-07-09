package com.oxygens.nativehook

/**
 * JNI bridge into the native-hook CMake module (plan §3, §4.6).
 *
 * Kotlin/Java call sites should go through this object rather than calling
 * `System.loadLibrary`/JNI methods directly, so there is one place to add
 * load-failure handling (e.g. missing `.so` for an unsupported ABI) as this grows.
 */
object NativeHookBridge {

    private var loaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("oxygens_nativehook")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e
        }
    }

    val isAvailable: Boolean get() = loaded

    /** Phase 0 pipeline proof (plan §6). Throws if the native library failed to load. */
    fun hello(): String {
        check(loaded) { "native-hook library not loaded: ${loadError?.message}" }
        return nativeHello()
    }

    /**
     * Attempts the JNIEnvExt hidden-API exemption (plan §4.6). Returns false (not an
     * exception) on failure — callers must treat false as "fall back to reflection or
     * report hook-incompatible", not as a fatal error, since this path is expected to
     * be unimplemented/unsupported on many API levels until the research spike lands.
     */
    fun tryExemptHiddenApi(): Boolean {
        if (!loaded) return false
        return nativeExemptHiddenApi()
    }

    private external fun nativeHello(): String
    private external fun nativeExemptHiddenApi(): Boolean
}
