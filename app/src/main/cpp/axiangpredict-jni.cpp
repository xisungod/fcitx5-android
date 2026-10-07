/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "axiangpredict.h"
#include <jni.h>
#include <cstdint>
#include <limits>
#include <mutex>
#include <unordered_map>

namespace {
std::mutex mutex;
std::unordered_map<jlong, std::unique_ptr<axiang::predict::Predictor>> predictors;
jlong next_handle = 1;
thread_local const char* status = "NotInitialized";
std::string Bytes(JNIEnv* env, jbyteArray bytes, jsize maximum) {
    if (!bytes) return {};
    const auto length = env->GetArrayLength(bytes);
    if (length < 0 || length > maximum) return {};
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeCreate(JNIEnv* env, jobject, jbyteArray path) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!predictors.empty()) { status = "AlreadyCreated"; return 0; }
        auto name = Bytes(env, path, 4096);
        if (env->ExceptionCheck()) { status = "JniFailure"; return 0; }
        auto predictor = std::make_unique<axiang::predict::Predictor>(name);
        if (next_handle == std::numeric_limits<jlong>::max()) { status = "HandleLimit"; return 0; }
        const auto handle = next_handle++;
        predictors.emplace(handle, std::move(predictor));
        status = "Ready";
        return handle;
    } catch (...) { status = "ModelUnavailable"; return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeQuery(JNIEnv* env, jobject, jlong handle,
                                                                  jbyteArray input, jint limit) {
    std::vector<std::string> values;
    {
        std::lock_guard<std::mutex> lock(mutex);
        status = "HandleUnavailable";
        auto found = predictors.find(handle);
        if (found != predictors.end()) {
            try {
                const auto context = Bytes(env, input, 256);
                if (env->ExceptionCheck()) { status = "JniFailure"; return nullptr; }
                values = found->second->Query(context, limit);
                status = "Ready";
            } catch (...) { status = "QueryFailed"; }
        }
    }
    const auto cls = env->FindClass("[B");
    if (!cls) return nullptr;
    const auto result = env->NewObjectArray(static_cast<jsize>(values.size()), cls, nullptr);
    env->DeleteLocalRef(cls);
    if (!result) return nullptr;
    for (size_t i = 0; i < values.size(); ++i) {
        const auto bytes = env->NewByteArray(static_cast<jsize>(values[i].size()));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(values[i].size()),
                               reinterpret_cast<const jbyte*>(values[i].data()));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeStatus(JNIEnv* env, jobject) {
    return env->NewStringUTF(status);
}
extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(mutex);
    predictors.erase(handle);
    status = "Closed";
}
