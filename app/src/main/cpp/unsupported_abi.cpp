#include <jni.h>

namespace {
void unsupported(JNIEnv * env) {
    env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"),
                  "Offline AI requires a supported 64-bit Android process");
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_open(JNIEnv * env, jobject, jint, jint, jint) {
    unsupported(env); return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_countTokens(JNIEnv * env, jobject, jlong, jbyteArray, jbyteArray) {
    unsupported(env); return 0;
}
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_generate(JNIEnv * env, jobject, jlong, jbyteArray, jbyteArray, jbyteArray, jint) {
    unsupported(env); return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_cancel(JNIEnv * env, jobject, jlong) { unsupported(env); }
extern "C" JNIEXPORT void JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_close(JNIEnv * env, jobject, jlong) { unsupported(env); }
