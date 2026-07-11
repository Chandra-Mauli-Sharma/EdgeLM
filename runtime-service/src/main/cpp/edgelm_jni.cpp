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

// Cached java.lang.String(byte[], "UTF-8") — used instead of NewStringUTF, which
// expects *modified* UTF-8 (CESU-8) and mangles standard 4-byte glyphs like emoji.
static jclass    g_stringClass    = nullptr;   // global ref
static jmethodID g_stringCtor     = nullptr;   // String(byte[], String charsetName)
static jmethodID g_stringGetBytes = nullptr;   // String.getBytes(String charsetName)
static jstring   g_utf8Name       = nullptr;   // global ref to "UTF-8"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK && env) {
        jclass local     = env->FindClass("java/lang/String");
        g_stringClass    = (jclass)env->NewGlobalRef(local);
        g_stringCtor     = env->GetMethodID(g_stringClass, "<init>", "([BLjava/lang/String;)V");
        g_stringGetBytes = env->GetMethodID(g_stringClass, "getBytes", "(Ljava/lang/String;)[B");
        g_utf8Name       = (jstring)env->NewGlobalRef(env->NewStringUTF("UTF-8"));
        env->DeleteLocalRef(local);
    }
    return JNI_VERSION_1_6;
}

// Correctly decode standard UTF-8 bytes into a Java String (handles emoji / CJK).
static jstring utf8_to_jstring(JNIEnv* e, const std::string& text) {
    if (!g_stringClass || !g_stringCtor) return e->NewStringUTF(text.c_str());  // fallback
    const jsize len = (jsize)text.size();
    jbyteArray arr = e->NewByteArray(len);
    if (!arr) return nullptr;
    e->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(text.data()));
    jstring js = (jstring)e->NewObject(g_stringClass, g_stringCtor, arr, g_utf8Name);
    e->DeleteLocalRef(arr);
    return js;
}

// Read a Java String as *standard* UTF-8 (GetStringUTFChars gives modified UTF-8,
// which corrupts 4-byte glyphs like emoji in the incoming prompt).
static std::string jstring_to_utf8(JNIEnv* e, jstring s) {
    if (!s) return "";
    if (!g_stringGetBytes) {                                  // fallback
        const char* c = e->GetStringUTFChars(s, nullptr);
        std::string r(c ? c : ""); if (c) e->ReleaseStringUTFChars(s, c); return r;
    }
    jbyteArray arr = (jbyteArray)e->CallObjectMethod(s, g_stringGetBytes, g_utf8Name);
    if (!arr) return "";
    const jsize len = e->GetArrayLength(arr);
    std::string out((size_t)len, '\0');
    if (len > 0) e->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(&out[0]));
    e->DeleteLocalRef(arr);
    return out;
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

    std::string sessionId = jstring_to_utf8(env, jsession);
    std::string prompt    = jstring_to_utf8(env, jprompt);

    // Global ref + method id survive across threads; the local jsink does not.
    jobject   gsink   = env->NewGlobalRef(jsink);
    jclass    cls     = env->GetObjectClass(jsink);
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "(Ljava/lang/String;)V");

    edgelm::Sink sink;
    sink.emit_chunk = [gsink, onChunk](const std::string& text) {
        JNIEnv* e = nullptr;
        if (g_vm->AttachCurrentThread(&e, nullptr) != JNI_OK || e == nullptr) return;
        jstring js = utf8_to_jstring(e, text);
        if (js) { e->CallVoidMethod(gsink, onChunk, js); e->DeleteLocalRef(js); }
    };
    sink.is_cancelled = []() -> bool { return false; };   // cancel handled via request_cancel

    int produced = edgelm::generate(m, sessionId, prompt, sink);  // blocks until this lane finishes
    env->DeleteGlobalRef(gsink);
    return produced;
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
