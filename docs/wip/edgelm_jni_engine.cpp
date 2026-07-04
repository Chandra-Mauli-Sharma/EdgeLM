#include <jni.h>
#include <string>
#include "llama_runner.h"

// JNI bindings for ai.edgelm.service.NativeBridge (a Kotlin `object`).
//
// IMPORTANT: the continuous-batching engine streams tokens from its OWN thread,
// not the thread that called generate(). JNIEnv is per-thread, so the callback
// must attach the engine thread to the JVM (via the cached JavaVM) and hold a
// GLOBAL ref to the sink — capturing the caller's JNIEnv/local ref would crash.

static JavaVM* g_vm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_edgelm_service_NativeBridge_loadModel(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    edgelm::Model* m = edgelm::load_model(path);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT jint JNICALL
Java_ai_edgelm_service_NativeBridge_generate(JNIEnv* env, jobject,
                                             jlong handle, jstring jsession, jstring jprompt, jobject jsink) {
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m) return 0;

    const char* s = env->GetStringUTFChars(jsession, nullptr);
    std::string sessionId(s ? s : "");
    env->ReleaseStringUTFChars(jsession, s);

    const char* p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p ? p : "");
    env->ReleaseStringUTFChars(jprompt, p);

    // Global ref + method id survive across threads; the local jsink does not.
    jobject   gsink   = env->NewGlobalRef(jsink);
    jclass    cls     = env->GetObjectClass(jsink);
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "(Ljava/lang/String;)V");

    edgelm::Sink sink;
    sink.emit_chunk = [gsink, onChunk](const std::string& text) {
        JNIEnv* e = nullptr;
        if (g_vm->AttachCurrentThread(&e, nullptr) != JNI_OK || e == nullptr) return;
