#include <jni.h>
#include <string>
#include "llama_runner.h"

// JNI bindings for ai.edgelm.service.NativeBridge (a Kotlin `object`, so the
// second arg is the singleton instance — unused here).

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
                                             jlong handle, jstring jprompt, jobject jsink) {
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m) return 0;

    const char* p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p ? p : "");
    env->ReleaseStringUTFChars(jprompt, p);

    // Resolve TokenSink.onChunk(String) and isCancelled():boolean once.
    jclass sinkClass   = env->GetObjectClass(jsink);
    jmethodID onChunk  = env->GetMethodID(sinkClass, "onChunk", "(Ljava/lang/String;)V");
    jmethodID isCancel = env->GetMethodID(sinkClass, "isCancelled", "()Z");

    edgelm::Sink sink;
    sink.emit_chunk = [&](const std::string& text) {
        jstring js = env->NewStringUTF(text.c_str());
        env->CallVoidMethod(jsink, onChunk, js);
        env->DeleteLocalRef(js);
    };
    sink.is_cancelled = [&]() -> bool {
        return env->CallBooleanMethod(jsink, isCancel) == JNI_TRUE;
    };

    return edgelm::generate(m, prompt, sink);
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_cancel(JNIEnv*, jobject, jlong handle) {
    edgelm::request_cancel(reinterpret_cast<edgelm::Model*>(handle));
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_unloadModel(JNIEnv*, jobject, jlong handle) {
    edgelm::unload_model(reinterpret_cast<edgelm::Model*>(handle));
}

} // extern "C"
