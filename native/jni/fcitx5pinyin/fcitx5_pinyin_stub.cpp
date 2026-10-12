/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Stub implementation of the fcitx5 pinyin JNI bridge.
 *
 * This is compiled instead of fcitx5_pinyin_jni.cpp when the libime/fcitx5 native
 * dependencies are not available at build time. It lets the app build and run: the Kotlin
 * side sees the library load but `nativeCreate` returns 0, so the engine reports itself as
 * unavailable (see Fcitx5Pinyin.isAvailable / Fcitx5PinyinUnavailableException) instead of
 * crashing.
 *
 * See tools/fcitx5/README.md for how to build with the real engine.
 */

#include <jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeCreate(
    JNIEnv * /*env*/, jobject /*thiz*/, jstring /*dictPath*/, jstring /*lmPath*/,
    jstring /*userDir*/, jstring /*userDictPath*/, jstring /*userHistoryPath*/) {
    return 0;
}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeDestroy(JNIEnv * /*env*/,
                                                                   jobject /*thiz*/,
                                                                   jlong /*handle*/) {}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeReset(JNIEnv * /*env*/,
                                                                 jobject /*thiz*/,
                                                                 jlong /*handle*/) {}

JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSetInput(JNIEnv *env,
                                                                    jobject /*thiz*/,
                                                                    jlong /*handle*/,
                                                                    jstring /*input*/) {
    return env->NewStringUTF("");
}

JNIEXPORT jobjectArray JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeCandidates(JNIEnv *env,
                                                                      jobject /*thiz*/,
                                                                      jlong /*handle*/) {
    return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
}

JNIEXPORT jboolean JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSelect(JNIEnv * /*env*/,
                                                                  jobject /*thiz*/,
                                                                  jlong /*handle*/,
                                                                  jint /*index*/) {
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSentence(JNIEnv *env,
                                                                    jobject /*thiz*/,
                                                                    jlong /*handle*/) {
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeCommit(JNIEnv *env,
                                                                  jobject /*thiz*/,
                                                                  jlong /*handle*/) {
    return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSelectedLength(JNIEnv * /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong /*handle*/) {
    return 0;
}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeClearSelection(JNIEnv * /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong /*handle*/) {}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSave(JNIEnv * /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong /*handle*/) {}

} // extern "C"
