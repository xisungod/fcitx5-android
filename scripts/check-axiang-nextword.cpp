/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Host mode verifies bounded Unicode handling. Android mode calls the actual
// compiled shared JNI bridge + original APK libIMECore using strict FakeJNI.
// FakeJNI is not ART, handset UI or a phone latency measurement.
#include "axiangpredict.h"
#include <algorithm>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#ifndef AXIANG_PREDICT_TEXT_ONLY
#include <dlfcn.h>
#include <jni.h>
#include <fstream>
#include <thread>
#endif

namespace {
int checks = 0;
void Require(bool value, const char* message) {
    ++checks;
    if (!value) throw std::runtime_error(message);
}
void TextChecks() {
    using axiang::predict::HanSuffix;
    Require(HanSuffix("你好") == "你好", "Known Han context");
    Require(HanSuffix("我想喝") == "我想喝", "Sentence remains available to tokenizer");
    Require(HanSuffix("你好。今天") == "今天", "Punctuation splits preceding context");
    Require(HanSuffix("你好😊今天") == "今天", "Emoji splits preceding context");
    Require(HanSuffix("你好😊").empty(), "Emoji at caret suppresses");
    Require(HanSuffix("你好。").empty(), "Punctuation at caret suppresses");
    Require(HanSuffix("").empty(), "Empty context suppressed");
    Require(HanSuffix("hello").empty(), "Latin-only context suppressed");
    Require(HanSuffix("𠀀") == "𠀀", "Supplementary Han preserved");
    Require(HanSuffix(std::string("\xc0\xaf", 2)).empty(), "Overlong UTF8 rejected");
    Require(HanSuffix(std::string("\xed\xa0\x80", 3)).empty(), "Surrogate UTF8 rejected");
    Require(HanSuffix(std::string("\xf4\x90\x80\x80", 4)).empty(), "Out of range UTF8 rejected");
    Require(HanSuffix(std::string("你好\0", 7)).empty(), "NUL rejected");
    Require(HanSuffix(std::string("\xe4\xbd", 2)).empty(), "Truncated UTF8 rejected");
    std::string maximum;
    for (int i = 0; i < 64; ++i) maximum += "你";
    Require(HanSuffix(maximum) == maximum, "64 codepoints accepted");
    Require(HanSuffix(maximum + "你").empty(), "Over budget context rejected");
    Require(!axiang::predict::HanCandidate("<unk>"), "Model marker excluded");
    Require(axiang::predict::HanCandidate("咖啡"), "Han candidate allowed");
}
#ifndef AXIANG_PREDICT_TEXT_ONLY
struct Object : _jobject { virtual ~Object() = default; };
struct Bytes : Object { std::string value; explicit Bytes(std::string v) : value(std::move(v)) {} };
struct String : Object { std::string value; explicit String(std::string v) : value(std::move(v)) {} };
struct Array : Object { std::vector<jobject> values; explicit Array(size_t n) : values(n) {} };
Object byte_class;
jclass FindClass(JNIEnv*, const char* name) {
    if (std::string(name) != "[B") throw std::runtime_error("Unexpected JNI class");
    return reinterpret_cast<jclass>(&byte_class);
}
jsize Length(JNIEnv*, jarray object) { return static_cast<jsize>(static_cast<Bytes*>(reinterpret_cast<jobject>(object))->value.size()); }
void GetRegion(JNIEnv*, jbyteArray object, jsize start, jsize length, jbyte* target) {
    const auto& value = static_cast<Bytes*>(reinterpret_cast<jobject>(object))->value;
    Require(start >= 0 && length >= 0 && static_cast<size_t>(start + length) <= value.size(), "JNI read bounds");
    std::copy(value.begin() + start, value.begin() + start + length, target);
}
jobjectArray NewArray(JNIEnv*, jsize size, jclass, jobject initial) {
    auto* array = new Array(static_cast<size_t>(size));
    std::fill(array->values.begin(), array->values.end(), initial);
    return reinterpret_cast<jobjectArray>(array);
}
jbyteArray NewBytes(JNIEnv*, jsize size) { return reinterpret_cast<jbyteArray>(new Bytes(std::string(static_cast<size_t>(size), '\0'))); }
void SetRegion(JNIEnv*, jbyteArray object, jsize start, jsize length, const jbyte* source) {
    auto& value = static_cast<Bytes*>(reinterpret_cast<jobject>(object))->value;
    Require(start >= 0 && length >= 0 && static_cast<size_t>(start + length) <= value.size(), "JNI write bounds");
    std::copy(source, source + length, value.begin() + start);
}
void SetElement(JNIEnv*, jobjectArray object, jsize index, jobject value) {
    auto* array = static_cast<Array*>(reinterpret_cast<jobject>(object));
    Require(index >= 0 && static_cast<size_t>(index) < array->values.size(), "JNI element bounds");
    auto* bytes = static_cast<Bytes*>(value);
    array->values[index] = new Bytes(bytes->value);
}
void Delete(JNIEnv*, jobject object) { if (object != &byte_class) delete static_cast<Object*>(object); }
jboolean Exception(JNIEnv*) { return JNI_FALSE; }
jstring NewString(JNIEnv*, const char* value) { return reinterpret_cast<jstring>(new String(value)); }
std::vector<std::string> Values(jobjectArray object) {
    if (!object) throw std::runtime_error("Unexpected null JNI result");
    auto* array = static_cast<Array*>(reinterpret_cast<jobject>(object));
    std::vector<std::string> output;
    for (auto value : array->values) { output.push_back(static_cast<Bytes*>(value)->value); delete static_cast<Bytes*>(value); }
    delete array;
    return output;
}
long long Nanos(std::chrono::steady_clock::time_point begin) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - begin).count();
}
long long Rss() {
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line)) if (line.rfind("VmRSS:", 0) == 0) return std::stoll(line.substr(6));
    return -1;
}
template<typename T> T Symbol(void* library, const char* name) {
    auto value = dlsym(library, name);
    if (!value) throw std::runtime_error("Required actual shared JNI export missing");
    return reinterpret_cast<T>(value);
}
#endif
}

int main(int argc, char** argv) {
    try {
        TextChecks();
#ifdef AXIANG_PREDICT_TEXT_ONLY
        std::cout << "{\"passed\":true,\"scope\":\"host_bounded_utf8_not_language_model\",\"checks\":" << checks << "}\n";
#else
        if (argc != 3) throw std::runtime_error("Expected shared bridge and actual model path");
        JNINativeInterface interface{};
        interface.FindClass = FindClass; interface.GetArrayLength = Length;
        interface.GetByteArrayRegion = GetRegion; interface.NewObjectArray = NewArray;
        interface.NewByteArray = NewBytes; interface.SetByteArrayRegion = SetRegion;
        interface.SetObjectArrayElement = SetElement; interface.DeleteLocalRef = Delete;
        interface.ExceptionCheck = Exception; interface.NewStringUTF = NewString;
        JNIEnv environment{&interface};
        const auto rss_before = Rss();
        auto begin = std::chrono::steady_clock::now();
        void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        const auto load = Nanos(begin);
        using Create = jlong (*)(JNIEnv*, jobject, jbyteArray);
        using Query = jobjectArray (*)(JNIEnv*, jobject, jlong, jbyteArray, jint);
        using Destroy = void (*)(JNIEnv*, jobject, jlong);
        using Status = jstring (*)(JNIEnv*, jobject);
        auto create = Symbol<Create>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeCreate");
        auto query = Symbol<Query>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeQuery");
        auto destroy = Symbol<Destroy>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeDestroy");
        auto status = Symbol<Status>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeStatus");
        auto status_string = [&]() { auto* value = static_cast<String*>(reinterpret_cast<jobject>(status(&environment, nullptr))); auto result = value->value; delete value; return result; };
        Bytes bad("/nonexistent/public-synthetic-model");
        Require(create(&environment, nullptr, reinterpret_cast<jbyteArray>(&bad)) == 0, "Missing main model fails closed");
        Bytes path(argv[2]);
        begin = std::chrono::steady_clock::now();
        const auto handle = create(&environment, nullptr, reinterpret_cast<jbyteArray>(&path));
        const auto initialization = Nanos(begin);
        Require(handle > 0 && status_string() == "Ready", "Actual libime model initialized");
        Require(create(&environment, nullptr, reinterpret_cast<jbyteArray>(&path)) == 0 && status_string() == "AlreadyCreated", "Duplicate handle rejected");
        std::vector<std::string> examples = {"你好", "你好啊", "小姑娘", "经常会", "改了么", "我想喝", "今天天气", "今天", "谢谢", "再见", "我正在打字", "明天去北京",
                                             "做", "我想做", "你在做", "想", "今天要"};
        std::cout << "{\"passed\":true,\"runtime\":\"Android_bionic_actual_shared_libime_FakeJNI_not_ART\",\"library_load_nanos\":" << load
                  << ",\"cold_initialization_nanos\":" << initialization << ",\"qemu_process_rss_before_kib\":" << rss_before << ",\"queries\":[";
        int nonempty = 0;
        for (size_t index = 0; index < examples.size(); ++index) {
            Bytes input(examples[index]); begin = std::chrono::steady_clock::now();
            auto output = Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&input), 5));
            auto elapsed = Nanos(begin);
            Require(status_string() == "Ready" && output.size() <= 5, "Query remains ready and bounded");
            for (const auto& value : output) Require(axiang::predict::HanCandidate(value), "Real candidate remains Han text");
            if (examples[index] == "你在做") Require(!output.empty() && output.front() == "什么", "Known-word segmentation puts what first after you are doing");
            if (examples[index] == "我想做") Require(std::find(output.begin(), output.begin() + std::min<size_t>(3, output.size()), "什么") != output.begin() + std::min<size_t>(3, output.size()), "Known-word segmentation puts what in first three after I want to do");
            if (examples[index] == "今天要") Require(output == std::vector<std::string>({"去", "和", "做", "不", "在"}), "Today stays a word rather than a forced character split");
            if (examples[index] == "谢谢") Require(!output.empty() && output.front() == "你", "Thanks keeps its useful existing continuation");
            if (examples[index] == "你好") Require(!output.empty() && output.front() == "吗", "Hello keeps its useful existing continuation");
            if (examples[index] == "我想喝") Require(std::find(output.begin(), output.end(), "茶") != output.end() && std::find(output.begin(), output.end(), "咖啡") != output.end(), "I want to drink keeps real content-word continuations");
            if (!output.empty()) ++nonempty;
            if (index) std::cout << ',';
            std::cout << "{\"context\":\"" << examples[index] << "\",\"query_nanos\":" << elapsed << ",\"candidates\":[";
            for (size_t i = 0; i < output.size(); ++i) { if (i) std::cout << ','; std::cout << '"' << output[i] << '"'; }
            std::cout << "]}";
        }
        // Suppression, unknown input and direct JNI invalid-handle/close guards.
        for (const auto& context : {"", "hello", "你好。", "你好😊", "𠀀", "\xc0\xaf"}) {
            Bytes input(context);
            Require(Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&input), 5)).empty(), "Empty/unknown/emoji/punctuation fail closed");
        }
        Bytes known("我想喝");
        Require(Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&known), 0)).empty(), "Zero query limit cannot search unboundedly");
        Require(Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&known), 99)).empty(), "Over query limit rejected");
        Require(Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&known), 33)).empty(), "Limit above internal pool rejected");
        Bytes doing("做");
        const auto pool = Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&doing), 32));
        Require(pool.size() <= 32 && std::find(pool.begin(), pool.end(), "什么") != pool.end(), "Full bounded Han pool retains a real model word beyond old raw top ten");
        Require(std::find(pool.begin(), pool.end(), "准备") != pool.end() && std::find(pool.begin(), pool.end(), "生意") != pool.end(), "Pool contains real multi-character alternatives rather than hardcoded examples");
        const auto one = Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&doing), 1));
        Require(!pool.empty() && one == std::vector<std::string>{pool.front()}, "Display limit does not change the model search pool");
        Require(Values(query(&environment, nullptr, -1, reinterpret_cast<jbyteArray>(&known), 5)).empty() && status_string() == "HandleUnavailable", "Invalid handle rejected");
        std::thread worker([&] { Require(!Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&known), 5)).empty(), "Serial bridge accepts separate worker thread"); });
        worker.join();
        destroy(&environment, nullptr, handle); destroy(&environment, nullptr, handle);
        Require(Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&known), 5)).empty(), "Query after double close fails closed");
        const auto recreated = create(&environment, nullptr, reinterpret_cast<jbyteArray>(&path));
        Require(recreated > handle, "Handle recreated without stale identity");
        destroy(&environment, nullptr, recreated);
        Require(nonempty == static_cast<int>(examples.size()), "All public examples must show actual-model coverage");
        std::cout << "],\"nonempty_queries\":" << nonempty << ",\"checks\":" << checks
                  << ",\"internal_candidate_pool_limit\":32,\"tokenization_plan_limit\":16,\"recent_greedy_tokens_refined\":3"
                  << ",\"quality_regressions\":{\"you_are_doing_what_first\":true,\"want_to_do_what_top3\":true,\"today_word_preserved\":true,\"thanks_hello_preserved\":true,\"real_full_pool_retained\":true}"
                  << ",\"qemu_process_rss_after_kib\":" << Rss()
                  << ",\"art_verified\":false,\"phone_latency_verified\":false,\"model_learning\":false}\n";
#endif
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Synthetic next-word test failed: " << error.what() << '\n';
        return 1;
    }
}
