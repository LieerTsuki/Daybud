#include <jni.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

// This deliberately has no dependency on llama/ggml. Query before resolving any
// NativeLlamaBindings JNI method, and load exactly one complete inference engine.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeCpuFeatures_hasArmDotProduct(JNIEnv *, jobject) {
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
