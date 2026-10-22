#include "hymt_core.h"
#include <jni.h>
#include <map>
#include <mutex>
#include <stdexcept>

namespace {
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<HyMtCore>> registry;
jlong sequence = 0;

/** @brief 通过登记编号获取共享实例，避免取消与释放并发导致悬空指针。 */
std::shared_ptr<HyMtCore> engine(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = registry.find(handle);
    if (found == registry.end()) throw std::runtime_error("Hy-MT2 已释放，请重新准备模型");
    return found->second;
}
/** @brief 读取 Java UTF-8 字节数组，避免 JNI modified UTF-8 损坏文字。 */
std::string bytes(JNIEnv * env, jbyteArray value) {
    if (!value) throw std::runtime_error("Hy-MT2 输入不能为空");
    const auto size = env->GetArrayLength(value);
    if (size > 32768) throw std::runtime_error("Hy-MT2 输入过长");
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
/** @brief 将完整 UTF-8 数据返回给 Java。 */
jbyteArray byteArray(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!result) throw std::runtime_error("Hy-MT2 内存不足");
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
/** @brief 把受控异常传给 Kotlin，由调用层决定提示或取消。 */
void fail(JNIEnv * env, const std::exception & error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_opennomi_app_screen_HyMtNative_open(JNIEnv * env, jobject, jbyteArray path, jint threads) {
    try {
        auto value = std::make_shared<HyMtCore>(bytes(env, path), threads);
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto handle = ++sequence;
        registry.emplace(handle, std::move(value));
        return handle;
    } catch (const std::exception & error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_opennomi_app_screen_HyMtNative_reset(JNIEnv * env, jobject, jlong handle) {
    try { engine(handle)->resetCancel(); } catch (const std::exception & error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_opennomi_app_screen_HyMtNative_cancel(JNIEnv *, jobject, jlong handle) {
    try { engine(handle)->cancel(); } catch (const std::exception &) { }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_opennomi_app_screen_HyMtNative_close(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<HyMtCore> retired;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto found = registry.find(handle);
        if (found == registry.end()) return;
        retired = std::move(found->second);
        registry.erase(found);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_opennomi_app_screen_HyMtNative_translate(JNIEnv * env, jobject, jlong handle, jbyteArray instruction, jobject sink) {
    try {
        if (!sink) throw std::runtime_error("Hy-MT2 缺少输出回调");
        const auto callback = env->GetMethodID(env->GetObjectClass(sink), "onBytes", "([B)V");
        if (!callback) return nullptr;
        const auto result = engine(handle)->translate(bytes(env, instruction), [&](const std::string & value) {
            auto data = byteArray(env, value);
            env->CallVoidMethod(sink, callback, data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck()) throw std::runtime_error("Hy-MT2 输出已取消");
        });
        return byteArray(env, result);
    } catch (const std::exception & error) { fail(env, error); return nullptr; }
}
