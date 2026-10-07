/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "rime-touch-probe.h"
#include <jni.h>
#include <pthread.h>
#include <cstdint>
#include <memory>
#include <unordered_map>

namespace {
using axiang::typing::RimeTouchProbe;
struct OwnedProbe {
    pthread_t owner;
    std::unique_ptr<RimeTouchProbe> probe;
};
std::unordered_map<int64_t, OwnedProbe> probes;
int64_t next_handle = 1;
thread_local std::string last_failure;

bool FcitxThread(JNIEnv* env) {
    const auto thread_class = env->FindClass("java/lang/Thread");
    if (!thread_class) return false;
    const auto current = env->GetStaticMethodID(thread_class, "currentThread", "()Ljava/lang/Thread;");
    const auto get_name = env->GetMethodID(thread_class, "getName", "()Ljava/lang/String;");
    if (!current || !get_name) return false;
    const auto thread = env->CallStaticObjectMethod(thread_class, current);
    const auto name = static_cast<jstring>(env->CallObjectMethod(thread, get_name));
    if (env->ExceptionCheck() || !name) return false;
    const auto chars = env->GetStringUTFChars(name, nullptr);
    if (!chars) return false;
    const bool correct = std::string(chars) == "fcitx-main";
    env->ReleaseStringUTFChars(name, chars);
    return correct;
}

std::string Utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    // Java's modified UTF-8 does not preserve supplementary Han characters.
    // Use the standard UTF-8 encoder before transferring bounded context.
    const auto string_class = env->FindClass("java/lang/String");
    const auto get_bytes = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
    const auto charset = env->NewStringUTF("UTF-8");
    const auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, get_bytes, charset));
    if (!bytes || env->ExceptionCheck()) return {};
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()),
                           reinterpret_cast<jbyte*>(result.data()));
    return result;
}

jstring JavaString(JNIEnv* env, const std::string& value) {
    const auto string_class = env->FindClass("java/lang/String");
    const auto constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    const auto charset = env->NewStringUTF("UTF-8");
    const auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                           reinterpret_cast<const jbyte*>(value.data()));
    return static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_core_RimeTouchProbe_nativeCreate(
    JNIEnv* env, jobject, jstring schema_id) {
    last_failure.clear();
    if (!FcitxThread(env)) { last_failure = "WrongThread"; return 0; }
    try {
        // At most one isolated translator exists. Never invoke setup, deploy,
        // initialize, finalize, cleanup-all, or create/alter a service session.
        if (!probes.empty()) { last_failure = "AlreadyCreated"; return 0; }
        const auto status = RimeTouchProbe::RuntimeStatus();
        if (status != "Ready") { last_failure = status; return 0; }
        auto probe = std::make_unique<RimeTouchProbe>(Utf8(env, schema_id));
        if (env->ExceptionCheck()) { last_failure = "JniFailure"; return 0; }
        const auto handle = next_handle++;
        probes.emplace(handle, OwnedProbe{pthread_self(), std::move(probe)});
        return handle;
    } catch (const axiang::typing::ProbeUnavailable& error) {
        last_failure = error.what();
        return 0;
    } catch (...) {
        last_failure = "CreateFailed";
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_core_RimeTouchProbe_nativeRuntimeStatus(
    JNIEnv* env, jobject) {
    if (!FcitxThread(env)) return JavaString(env, "WrongThread");
    try {
        const auto runtime = RimeTouchProbe::RuntimeStatus();
        // Maintenance/component readiness takes precedence over a remembered
        // failure. Successful create/query clears that failure on this thread.
        return JavaString(env, runtime != "Ready" ? runtime :
            last_failure.empty() ? "Ready:1.16.1" : last_failure);
    } catch (...) {
        return JavaString(env, "RuntimeUnavailable");
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_fcitx_fcitx5_android_core_RimeTouchProbe_nativeQuery(
    JNIEnv* env, jobject, jlong handle, jstring input, jstring context, jlong budget_nanos) {
    const auto candidate_class = env->FindClass(
        "org/fcitx/fcitx5/android/core/RimeTouchProbe$Candidate");
    if (!candidate_class) return nullptr;
    const auto constructor = env->GetMethodID(candidate_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;III)V");
    if (!constructor) return nullptr;
    std::vector<axiang::typing::ProbeCandidate> candidates;
    last_failure.clear();
    if (FcitxThread(env)) {
        const auto found = probes.find(handle);
        if (found != probes.end() && pthread_equal(found->second.owner, pthread_self())) {
            try {
                const auto keys = Utf8(env, input);
                const auto preceding = Utf8(env, context);
                const auto runtime = RimeTouchProbe::RuntimeStatus();
                if (runtime != "Ready") last_failure = runtime;
                else if (!env->ExceptionCheck()) candidates = found->second.probe->Query(
                    keys, preceding, std::chrono::nanoseconds(budget_nanos));
                else last_failure = "JniFailure";
            } catch (...) { last_failure = "QueryFailed"; candidates.clear(); }
        } else {
            last_failure = "HandleUnavailable";
        }
    } else last_failure = "WrongThread";
    if (env->ExceptionCheck()) return nullptr;
    const auto output = env->NewObjectArray(static_cast<jsize>(candidates.size()), candidate_class, nullptr);
    if (!output) return nullptr;
    for (size_t i = 0; i < candidates.size(); ++i) {
        const auto& candidate = candidates[i];
        const auto text = JavaString(env, candidate.text);
        const auto comment = JavaString(env, candidate.comment);
        const auto object = env->NewObject(candidate_class, constructor,
            text, comment, candidate.start, candidate.end, candidate.rank);
        env->SetObjectArrayElement(output, static_cast<jsize>(i), object);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(object);
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_RimeTouchProbe_nativeDestroy(
    JNIEnv* env, jobject, jlong handle) {
    if (!FcitxThread(env)) return;
    const auto found = probes.find(handle);
    if (found != probes.end() && pthread_equal(found->second.owner, pthread_self())) probes.erase(found);
}
