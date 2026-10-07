/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Executes the exact ARM64 shared JNI bridge under Android bionic with a strict
// minimal FakeJNI environment. This is not ART, a handset, or a latency test.
#include "axiangpredict.h"
#include <algorithm>
#include <dlfcn.h>
#include <fstream>
#include <iostream>
#include <jni.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Object : _jobject { virtual ~Object() = default; };
struct Bytes : Object { std::string value; explicit Bytes(std::string v) : value(std::move(v)) {} };
struct String : Object { std::string value; explicit String(std::string v) : value(std::move(v)) {} };
struct Array : Object { std::vector<jobject> values; explicit Array(size_t n) : values(n) {} };
Object byte_class;
void Require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
jclass FindClass(JNIEnv*, const char* name) {
    Require(std::string(name) == "[B", "Unexpected JNI class");
    return reinterpret_cast<jclass>(&byte_class);
}
jsize Length(JNIEnv*, jarray object) {
    return static_cast<jsize>(static_cast<Bytes*>(reinterpret_cast<jobject>(object))->value.size());
}
void GetRegion(JNIEnv*, jbyteArray object, jsize start, jsize length, jbyte* target) {
    const auto& value = static_cast<Bytes*>(reinterpret_cast<jobject>(object))->value;
    Require(start >= 0 && length >= 0 && static_cast<size_t>(start + length) <= value.size(), "JNI read bounds");
    std::copy(value.begin() + start, value.begin() + start + length, target);
}
jobjectArray NewArray(JNIEnv*, jsize size, jclass, jobject initial) {
    Require(size >= 0 && size <= 32, "JNI array bounds");
    auto* array = new Array(static_cast<size_t>(size));
    std::fill(array->values.begin(), array->values.end(), initial);
    return reinterpret_cast<jobjectArray>(array);
}
jbyteArray NewBytes(JNIEnv*, jsize size) {
    Require(size >= 0 && size <= 1024, "JNI byte-array bounds");
    return reinterpret_cast<jbyteArray>(new Bytes(std::string(static_cast<size_t>(size), '\0')));
}
void SetRegion(JNIEnv*, jbyteArray object, jsize start, jsize length, const jbyte* source) {
    auto& value = static_cast<Bytes*>(reinterpret_cast<jobject>(object))->value;
    Require(start >= 0 && length >= 0 && static_cast<size_t>(start + length) <= value.size(), "JNI write bounds");
    std::copy(source, source + length, value.begin() + start);
}
void SetElement(JNIEnv*, jobjectArray object, jsize index, jobject value) {
    auto* array = static_cast<Array*>(reinterpret_cast<jobject>(object));
    Require(index >= 0 && static_cast<size_t>(index) < array->values.size(), "JNI element bounds");
    array->values[index] = new Bytes(static_cast<Bytes*>(value)->value);
}
void Delete(JNIEnv*, jobject object) {
    if (object && object != &byte_class) delete static_cast<Object*>(object);
}
jboolean Exception(JNIEnv*) { return JNI_FALSE; }
jstring NewString(JNIEnv*, const char* value) {
    return reinterpret_cast<jstring>(new String(value));
}
std::vector<std::string> Values(jobjectArray object) {
    Require(object != nullptr, "Unexpected null JNI result");
    auto* array = static_cast<Array*>(reinterpret_cast<jobject>(object));
    std::vector<std::string> result;
    for (auto value : array->values) {
        Require(value != nullptr, "Unexpected null JNI candidate");
        result.push_back(static_cast<Bytes*>(value)->value);
        delete static_cast<Bytes*>(value);
    }
    delete array;
    return result;
}
template<typename T> T Symbol(void* library, const char* name) {
    auto value = dlsym(library, name);
    Require(value != nullptr, "Required actual JNI export missing");
    return reinterpret_cast<T>(value);
}
}

int main(int argc, char** argv) {
    try {
        Require(argc == 4, "Expected actual shared bridge, actual main model, and public context file");
        std::ifstream input(argv[3]);
        Require(static_cast<bool>(input), "Context file cannot be opened");
        std::vector<std::string> contexts;
        for (std::string line; std::getline(input, line);) {
            Require(!line.empty() && axiang::predict::HanSuffix(line) == line, "Invalid public Han context");
            contexts.push_back(std::move(line));
        }
        Require(contexts.size() == 200, "Full benchmark requires 200 contexts");
        JNINativeInterface interface{};
        interface.FindClass = FindClass;
        interface.GetArrayLength = Length;
        interface.GetByteArrayRegion = GetRegion;
        interface.NewObjectArray = NewArray;
        interface.NewByteArray = NewBytes;
        interface.SetByteArrayRegion = SetRegion;
        interface.SetObjectArrayElement = SetElement;
        interface.DeleteLocalRef = Delete;
        interface.ExceptionCheck = Exception;
        interface.NewStringUTF = NewString;
        JNIEnv environment{&interface};
        void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        using Create = jlong (*)(JNIEnv*, jobject, jbyteArray);
        using Query = jobjectArray (*)(JNIEnv*, jobject, jlong, jbyteArray, jint);
        using Destroy = void (*)(JNIEnv*, jobject, jlong);
        using Status = jstring (*)(JNIEnv*, jobject);
        auto create = Symbol<Create>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeCreate");
        auto query = Symbol<Query>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeQuery");
        auto destroy = Symbol<Destroy>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeDestroy");
        auto status = Symbol<Status>(library, "Java_org_fcitx_fcitx5_android_core_LibimeNextWordPredictor_nativeStatus");
        Bytes path(argv[2]);
        const auto handle = create(&environment, nullptr, reinterpret_cast<jbyteArray>(&path));
        Require(handle != 0, "Actual native model initialization failed");
        for (const auto& context : contexts) {
            Bytes text(context);
            auto values = Values(query(&environment, nullptr, handle, reinterpret_cast<jbyteArray>(&text), 32));
            auto* state = static_cast<String*>(reinterpret_cast<jobject>(status(&environment, nullptr)));
            const bool ready = state->value == "Ready";
            delete state;
            Require(ready, "Actual native query failed");
            Require(values.size() <= 32, "Actual native query exceeded bound");
            std::cout << "PUBLIC_NATIVE_POOL\t" << context << '\t';
            for (size_t index = 0; index < values.size(); ++index) {
                Require(axiang::predict::HanCandidate(values[index]), "Invalid actual native candidate");
                if (index) std::cout << ',';
                std::cout << values[index];
            }
            std::cout << '\n';
        }
        destroy(&environment, nullptr, handle);
        dlclose(library);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
