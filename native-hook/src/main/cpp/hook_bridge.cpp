#include <jni.h>
#include <android/log.h>
#include "inline_hook_engine.h"

#define LOG_TAG "OxygenSNativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_oxygens_nativehook_NativeHookBridge_nativeHello(JNIEnv *env, jobject /* this */) {
    // Phase 0 definition of done (plan §6): "a trivial JNI hello world hook compiling
    // and linking" — this function is that proof point for the NDK build pipeline.
    LOGI("native-hook bridge loaded and callable");
    return env->NewStringUTF("oxygens-native-hook-ok");
}

// Hidden-API JNI exemption entry point (plan §4.6): "use the JNI-based
// art::JNIEnvExt hidden-API exemption technique ... as your primary access path".
//
// NOT IMPLEMENTED. The real technique requires locating ART-internal struct offsets
// (JNIEnvExt's hidden-API-policy field) that differ per ART build / Android version,
// which must be derived from a real device + the matching AOSP source tree, not
// guessed at here. Returns JNI_FALSE until that research spike is done and the result
// is wired in per api-level compat module (see compat/apiNN/ApiNNCompat.kt).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_oxygens_nativehook_NativeHookBridge_nativeExemptHiddenApi(JNIEnv *env, jobject /* this */) {
    (void) env;
    LOGI("nativeExemptHiddenApi: not implemented — research spike required (see compat module docs)");
    return JNI_FALSE;
}
