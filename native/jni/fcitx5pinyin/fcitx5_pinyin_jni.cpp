/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * JNI bridge exposing the fcitx5 pinyin engine (libime) to FUTO Keyboard.
 *
 * fcitx5-android implements its pinyin input method on top of libime:
 *   - a shared libime::PinyinIME (system dictionary + user language model), and
 *   - a per-composition libime::PinyinContext.
 *
 * This file mirrors that usage. It links against libime (and the fcitx-utils that libime
 * depends on) which are provided by the fcitx5-android native build. See
 * tools/fcitx5/README.md for how to obtain/build those libraries.
 *
 * When the libime headers/libs are not available, CMake builds fcitx5_pinyin_stub.cpp
 * instead; the Kotlin side then reports the engine as unavailable instead of crashing.
 */

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <fstream>

#include <libime/core/userlanguagemodel.h>
#include <libime/pinyin/pinyincontext.h>
#include <libime/pinyin/pinyindictionary.h>
#include <libime/pinyin/pinyinime.h>

namespace {

constexpr char kSeparator = '\x01';

struct PinyinHandle {
    std::unique_ptr<libime::PinyinIME> ime;
    std::unique_ptr<libime::PinyinContext> context;
    std::string lastInput;
    std::string userDictPath;
    std::string userHistoryPath;
    bool enableLearning = true;
    std::mutex mutex;
};

std::string jstringToStdString(JNIEnv *env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

jstring stdStringToJstring(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

PinyinHandle *fromHandle(jlong handle) {
    return reinterpret_cast<PinyinHandle *>(handle);
}

// Rebuilds the context by replaying `input`, dropping any current selection.
void rebuildContext(PinyinHandle *handle, const std::string &input) {
    handle->context = std::make_unique<libime::PinyinContext>(handle->ime.get());
    handle->context->setMaxSentenceLength(35);
    if (!input.empty()) {
        handle->context->type(std::string_view(input));
    }
    handle->lastInput = input;
}

void loadUserData(PinyinHandle *handle) {
    if (!handle->enableLearning) {
        return;
    }
    {
        std::ifstream in(handle->userDictPath, std::ios::binary);
        if (in.is_open()) {
            try {
                handle->ime->dict()->load(libime::PinyinDictionary::UserDict, in,
                                          libime::PinyinDictFormat::Binary);
            } catch (...) {
                // Corrupt user dictionary: ignore, it will be recreated on save.
            }
        }
    }
    {
        std::ifstream in(handle->userHistoryPath, std::ios::binary);
        if (in.is_open()) {
            try {
                handle->ime->model()->load(in);
            } catch (...) {
            }
        }
    }
}

void saveUserData(PinyinHandle *handle) {
    if (!handle->enableLearning) {
        return;
    }
    {
        std::ofstream out(handle->userDictPath, std::ios::binary | std::ios::trunc);
        if (out.is_open()) {
            try {
                handle->ime->dict()->save(libime::PinyinDictionary::UserDict,
                                          out, libime::PinyinDictFormat::Binary);
            } catch (...) {
            }
        }
    }
    {
        std::ofstream out(handle->userHistoryPath, std::ios::binary | std::ios::trunc);
        if (out.is_open()) {
            try {
                handle->ime->model()->save(out);
            } catch (...) {
            }
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeCreate(
    JNIEnv *env, jobject /*thiz*/, jstring dictPath, jstring lmPath, jstring userDir,
    jstring userDictPath, jstring userHistoryPath) {
    const std::string dict = jstringToStdString(env, dictPath);
    const std::string lm = jstringToStdString(env, lmPath);
    const std::string userDict = jstringToStdString(env, userDictPath);
    const std::string userHistory = jstringToStdString(env, userHistoryPath);
    (void)userDir;

    try {
        auto handle = std::make_unique<PinyinHandle>();
        handle->ime = std::make_unique<libime::PinyinIME>(
            std::make_unique<libime::PinyinDictionary>(),
            std::make_unique<libime::UserLanguageModel>(lm.c_str()));

        // System dictionary (built-in, read-only).
        try {
            handle->ime->dict()->load(libime::PinyinDictionary::SystemDict, dict.c_str(),
                                      libime::PinyinDictFormat::Binary);
        } catch (...) {
            return 0;
        }

        handle->ime->setScoreFilter(1);
        handle->userDictPath = userDict;
        handle->userHistoryPath = userHistory;
        loadUserData(handle.get());

        rebuildContext(handle.get(), "");

        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeDestroy(JNIEnv * /*env*/,
                                                                   jobject /*thiz*/,
                                                                   jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        saveUserData(h);
    }
    delete h;
}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeReset(JNIEnv * /*env*/,
                                                                 jobject /*thiz*/,
                                                                 jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    rebuildContext(h, "");
}


JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSetInput(JNIEnv *env,
                                                                    jobject /*thiz*/,
                                                                    jlong handle,
                                                                    jstring input) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return stdStringToJstring(env, "");
    }
    std::lock_guard<std::mutex> lock(h->mutex);

    const std::string text = jstringToStdString(env, input);
    try {
        if (text == h->lastInput) {
            // No change.
        } else if (text.size() > h->lastInput.size() &&
                   text.compare(0, h->lastInput.size(), h->lastInput) == 0) {
            // Appended characters: type only the suffix, preserving any selection.
            h->context->type(std::string_view(text).substr(h->lastInput.size()));
            h->lastInput = text;
        } else if (text.size() < h->lastInput.size() &&
                   h->lastInput.compare(0, text.size(), text) == 0) {
            // Removed trailing characters.
            const size_t removed = h->lastInput.size() - text.size();
            for (size_t i = 0; i < removed; ++i) {
                h->context->backspace();
            }
            h->lastInput = text;
        } else {
            rebuildContext(h, text);
        }
        return stdStringToJstring(env, h->context->preedit());
    } catch (...) {
        return stdStringToJstring(env, "");
    }
}

JNIEXPORT jobjectArray JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeCandidates(JNIEnv *env,
                                                                      jobject /*thiz*/,
                                                                      jlong handle) {
    jclass stringClass = env->FindClass("java/lang/String");
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }
    std::lock_guard<std::mutex> lock(h->mutex);

    const auto &candidates = h->context->candidates();
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(candidates.size()), stringClass, nullptr);
    for (size_t i = 0; i < candidates.size(); ++i) {
        std::string packed = candidates[i].toString();
        packed.push_back(kSeparator);
        try {
            packed += h->context->candidateFullPinyin(i);
        } catch (...) {
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i),
                                   stdStringToJstring(env, packed));
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSelect(JNIEnv * /*env*/,
                                                                  jobject /*thiz*/,
                                                                  jlong handle, jint index) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    try {
        h->context->select(static_cast<size_t>(index));
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSentence(JNIEnv *env,
                                                                    jobject /*thiz*/,
                                                                    jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return stdStringToJstring(env, "");
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    try {
        return stdStringToJstring(env, h->context->sentence());
    } catch (...) {
        return stdStringToJstring(env, "");
    }
}

JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeCommit(JNIEnv *env,
                                                                  jobject /*thiz*/,
                                                                  jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return stdStringToJstring(env, "");
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    std::string committed;
    try {
        committed = h->context->sentence();
        h->context->learn();
    } catch (...) {
    }
    rebuildContext(h, "");
    return stdStringToJstring(env, committed);
}

JNIEXPORT jint JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSelectedLength(JNIEnv * /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    try {
        return static_cast<jint>(h->context->selectedLength());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeClearSelection(JNIEnv * /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    try {
        rebuildContext(h, h->lastInput);
    } catch (...) {
    }
}

JNIEXPORT void JNICALL
Java_org_futo_inputmethod_engine_fcitx5_Fcitx5Pinyin_nativeSave(JNIEnv * /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong handle) {
    auto *h = fromHandle(handle);
    if (h == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    saveUserData(h);
}

} // extern "C"


