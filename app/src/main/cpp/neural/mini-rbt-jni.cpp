/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include <jni.h>
#include <dlfcn.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
const OrtApi* api() {
    static const OrtApi* value = [] {
        // Reuse the already bundled speech runtime; do not package a second ORT.
        void* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("Local neural runtime unavailable");
        auto getter = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(library, "OrtGetApiBase"));
        if (!getter) throw std::runtime_error("Local neural runtime has no C API");
        const OrtApi* result = getter()->GetApi(ORT_API_VERSION);
        if (!result) throw std::runtime_error("Local neural runtime API incompatible");
        return result;
    }();
    return value;
}

void check(OrtStatus* status) {
    if (!status) return;
    std::string message = api()->GetErrorMessage(status);
    api()->ReleaseStatus(status);
    throw std::runtime_error(message);
}

struct Session {
    OrtEnv* env = nullptr;
    OrtSession* session = nullptr;
    OrtMemoryInfo* memory = nullptr;
    ~Session() {
        if (session) api()->ReleaseSession(session);
        if (memory) api()->ReleaseMemoryInfo(memory);
        if (env) api()->ReleaseEnv(env);
    }
};

struct Options {
    OrtSessionOptions* value = nullptr;
    ~Options() { if (value) api()->ReleaseSessionOptions(value); }
};

struct Tensors {
    std::vector<OrtValue*> inputs;
    OrtValue* output = nullptr;
    ~Tensors() {
        for (auto* input : inputs) if (input) api()->ReleaseValue(input);
        if (output) api()->ReleaseValue(output);
    }
};

void failure(JNIEnv* jni, const std::exception& error) {
    jni->ThrowNew(jni->FindClass("java/lang/IllegalStateException"), error.what());
}

std::vector<int64_t> read(JNIEnv* jni, jlongArray array, size_t expected) {
    if (!array || static_cast<size_t>(jni->GetArrayLength(array)) != expected)
        throw std::runtime_error("Invalid neural tensor dimensions");
    std::vector<jlong> values(expected);
    jni->GetLongArrayRegion(array, 0, static_cast<jsize>(expected), values.data());
    return std::vector<int64_t>(values.begin(), values.end());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_input_neural_MiniRbtNative_create(JNIEnv* jni, jclass, jstring path) {
    try {
        if (!path) throw std::runtime_error("Missing neural model path");
        const char* chars = jni->GetStringUTFChars(path, nullptr);
        if (!chars) return 0;
        std::string model(chars);
        jni->ReleaseStringUTFChars(path, chars);
        auto result = std::make_unique<Session>();
        Options options;
        check(api()->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "AXiangMiniRbt", &result->env));
        check(api()->CreateSessionOptions(&options.value));
        check(api()->SetIntraOpNumThreads(options.value, 1));
        check(api()->SetInterOpNumThreads(options.value, 1));
        check(api()->SetSessionGraphOptimizationLevel(options.value, ORT_ENABLE_BASIC));
        check(api()->CreateSession(result->env, model.c_str(), options.value, &result->session));
        check(api()->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &result->memory));
        return reinterpret_cast<jlong>(result.release());
    } catch (const std::exception& error) { failure(jni, error); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_fcitx_fcitx5_android_input_neural_MiniRbtNative_score(
        JNIEnv* jni, jclass, jlong handle, jlongArray ids, jlongArray mask, jlongArray types,
        jlongArray positions, jlongArray targets, jint batch, jint length) {
    try {
        if (!handle || batch < 1 || batch > 48 || length < 3 || length > 96)
            throw std::runtime_error("Invalid neural scoring request");
        auto* session = reinterpret_cast<Session*>(handle);
        std::vector<std::vector<int64_t>> data;
        data.push_back(read(jni, ids, static_cast<size_t>(batch) * length));
        data.push_back(read(jni, mask, static_cast<size_t>(batch) * length));
        data.push_back(read(jni, types, static_cast<size_t>(batch) * length));
        data.push_back(read(jni, positions, batch));
        data.push_back(read(jni, targets, batch));
        for (auto token : data[0]) if (token < 0 || token >= 21128)
            throw std::runtime_error("Invalid neural input token");
        for (auto value : data[1]) if (value != 0 && value != 1)
            throw std::runtime_error("Invalid neural attention mask");
        for (auto value : data[2]) if (value != 0 && value != 1)
            throw std::runtime_error("Invalid neural token type");
        for (int i = 0; i < batch; ++i)
            if (data[3][i] < 0 || data[3][i] >= length || data[4][i] < 0 || data[4][i] >= 21128)
                throw std::runtime_error("Invalid neural token index");
        Tensors tensors;
        int64_t matrixShape[] = {batch, length};
        int64_t vectorShape[] = {batch};
        for (size_t i = 0; i < data.size(); ++i) {
            OrtValue* value = nullptr;
            check(api()->CreateTensorWithDataAsOrtValue(session->memory, data[i].data(),
                    data[i].size() * sizeof(int64_t), i < 3 ? matrixShape : vectorShape,
                    i < 3 ? 2 : 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &value));
            tensors.inputs.push_back(value);
        }
        const char* inputNames[] = {"input_ids", "attention_mask", "token_type_ids", "masked_position", "target_id"};
        const char* outputNames[] = {"log_prob"};
        std::vector<const OrtValue*> inputs(tensors.inputs.begin(), tensors.inputs.end());
        check(api()->Run(session->session, nullptr, inputNames, inputs.data(), inputs.size(),
                outputNames, 1, &tensors.output));
        OrtTensorTypeAndShapeInfo* info = nullptr;
        check(api()->GetTensorTypeAndShape(tensors.output, &info));
        size_t count = 0;
        ONNXTensorElementDataType type;
        auto status = api()->GetTensorShapeElementCount(info, &count);
        auto typeStatus = api()->GetTensorElementType(info, &type);
        api()->ReleaseTensorTypeAndShapeInfo(info);
        check(status); check(typeStatus);
        if (count != static_cast<size_t>(batch) || type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
            throw std::runtime_error("Unexpected neural model output");
        float* scores = nullptr;
        check(api()->GetTensorMutableData(tensors.output, reinterpret_cast<void**>(&scores)));
        for (int i = 0; i < batch; ++i)
            if (!std::isfinite(scores[i])) throw std::runtime_error("Invalid neural model score");
        auto output = jni->NewFloatArray(batch);
        if (output) jni->SetFloatArrayRegion(output, 0, batch, scores);
        return output;
    } catch (const std::exception& error) { failure(jni, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_neural_MiniRbtNative_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
