#include <jni.h>
#include <string>
#include <vector>
#include "llama_runner.h"
#ifdef EDGELM_BATCHED
#include "batched_runner.h"
#endif
#ifdef EDGELM_VISION
#include "vision_runner.h"
#endif

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
Java_ai_edgelm_service_NativeBridge_setSystemPrompt(JNIEnv* env, jobject, jlong handle, jstring jsystem) {
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m) return;
    std::string s = jstring_to_utf8(env, jsystem);
    edgelm::set_system_prompt(m, s.c_str());
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_setGrammar(JNIEnv* env, jobject, jlong handle, jstring jgbnf) {
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m) return;
    std::string g = jstring_to_utf8(env, jgbnf);
    edgelm::set_grammar(m, g.c_str());
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_unloadModel(JNIEnv*, jobject, jlong handle) {
    edgelm::unload_model(reinterpret_cast<edgelm::Model*>(handle));
}

JNIEXPORT jstring JNICALL
Java_ai_edgelm_service_NativeBridge_engineLabel(JNIEnv* env, jobject) {
    return utf8_to_jstring(env, std::string(edgelm::engine_label()));
}

JNIEXPORT jboolean JNICALL
Java_ai_edgelm_service_NativeBridge_attachDraft(JNIEnv* env, jobject, jlong handle, jstring jpath) {
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    const bool ok = edgelm::attach_draft(m, p ? p : "");
    if (p) env->ReleaseStringUTFChars(jpath, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Increment 2 (continuous batching) test entry. Always present so the Kotlin external
// resolves; the real engine is compiled only with -DEDGELM_BATCHED=ON, otherwise this
// returns 0. Submits every prompt on its own sequence and drives one batched decode
// loop, streaming each sequence's tokens back via sink.onChunk(seq, text). Runs on THIS
// thread, so the cached env is valid throughout (no AttachCurrentThread needed).
JNIEXPORT jint JNICALL
Java_ai_edgelm_service_NativeBridge_batchedRunTest(JNIEnv* env, jobject,
                                                   jlong handle, jobjectArray jprompts, jobject jsink) {
#ifdef EDGELM_BATCHED
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m || !jprompts || !jsink) return 0;

    const jsize n = env->GetArrayLength(jprompts);
    std::vector<std::string> prompts;
    prompts.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(jprompts, i);
        prompts.push_back(jstring_to_utf8(env, js));
        if (js) env->DeleteLocalRef(js);
    }

    jobject   gsink   = env->NewGlobalRef(jsink);
    jclass    cls     = env->GetObjectClass(jsink);
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "(ILjava/lang/String;)V");

    auto on_chunk = [env, gsink, onChunk](int seq, const std::string& text) {
        jstring js = utf8_to_jstring(env, text);
        if (js) { env->CallVoidMethod(gsink, onChunk, (jint)seq, js); env->DeleteLocalRef(js); }
    };

    const int produced = edgelm::run_batched_test(m, prompts, on_chunk);
    env->DeleteGlobalRef(gsink);
    return produced;
#else
    (void)env; (void)handle; (void)jprompts; (void)jsink;
    return 0;   // built without -DEDGELM_BATCHED
#endif
}

// ---- Embeddings (Phase 2) ---------------------------------------------------

JNIEXPORT jlong JNICALL
Java_ai_edgelm_service_NativeBridge_loadEmbeddingModel(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    edgelm::Model* m = edgelm::load_embedding_model(path);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT jint JNICALL
Java_ai_edgelm_service_NativeBridge_embedDim(JNIEnv*, jobject, jlong handle) {
    return edgelm::embed_dim(reinterpret_cast<edgelm::Model*>(handle));
}

JNIEXPORT jfloatArray JNICALL
Java_ai_edgelm_service_NativeBridge_embed(JNIEnv* env, jobject, jlong handle, jstring jtext) {
    auto* m = reinterpret_cast<edgelm::Model*>(handle);
    if (!m) return nullptr;
    std::string text = jstring_to_utf8(env, jtext);
    std::vector<float> vec;
    const int dim = edgelm::embed(m, text, vec);
    if (dim <= 0) return nullptr;
    jfloatArray arr = env->NewFloatArray(dim);
    if (!arr) return nullptr;
    env->SetFloatArrayRegion(arr, 0, dim, vec.data());
    return arr;
}

// ---- Vision / multimodal (Phase 2) ------------------------------------------
// Guarded; stubs when -DEDGELM_VISION is off so the Kotlin externals still link.

JNIEXPORT jlong JNICALL
Java_ai_edgelm_service_NativeBridge_loadVisionModel(JNIEnv* env, jobject, jstring jmodel, jstring jmmproj) {
#ifdef EDGELM_VISION
    std::string model  = jstring_to_utf8(env, jmodel);
    std::string mmproj = jstring_to_utf8(env, jmmproj);
    return reinterpret_cast<jlong>(edgelm::load_vision_model(model.c_str(), mmproj.c_str()));
#else
    (void)env; (void)jmodel; (void)jmmproj; return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_ai_edgelm_service_NativeBridge_visionGenerate(JNIEnv* env, jobject,
                                                   jlong handle, jstring jprompt, jbyteArray jimage, jobject jsink) {
#ifdef EDGELM_VISION
    auto* m = reinterpret_cast<edgelm::VisionModel*>(handle);
    if (!m || !jimage || !jsink) return 0;
    std::string prompt = jstring_to_utf8(env, jprompt);
    const jsize len = env->GetArrayLength(jimage);
    std::vector<uint8_t> img((size_t)len);
    if (len > 0) env->GetByteArrayRegion(jimage, 0, len, reinterpret_cast<jbyte*>(img.data()));

    jclass    cls     = env->GetObjectClass(jsink);
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "(Ljava/lang/String;)V");
    jmethodID isCanc  = env->GetMethodID(cls, "isCancelled", "()Z");
    edgelm::Sink sink;                                   // runs on THIS thread → env is valid
    sink.emit_chunk = [env, jsink, onChunk](const std::string& t) {
        jstring js = utf8_to_jstring(env, t);
        if (js) { env->CallVoidMethod(jsink, onChunk, js); env->DeleteLocalRef(js); }
    };
    sink.is_cancelled = [env, jsink, isCanc]() -> bool { return env->CallBooleanMethod(jsink, isCanc) == JNI_TRUE; };

    return edgelm::vision_generate(m, prompt, img.data(), (int)len, sink);
#else
    (void)env; (void)handle; (void)jprompt; (void)jimage; (void)jsink; return 0;
#endif
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_unloadVisionModel(JNIEnv*, jobject, jlong handle) {
#ifdef EDGELM_VISION
    edgelm::unload_vision_model(reinterpret_cast<edgelm::VisionModel*>(handle));
#else
    (void)handle;
#endif
}

// ---- Increment 2 service integration: persistent BatchedRuntime -------------
// All guarded; stubs when -DEDGELM_BATCHED is off so the Kotlin externals still link.

JNIEXPORT jlong JNICALL
Java_ai_edgelm_service_NativeBridge_batchedCreate(JNIEnv*, jobject, jlong modelHandle, jint pool, jint nctx) {
#ifdef EDGELM_BATCHED
    auto* m = reinterpret_cast<edgelm::Model*>(modelHandle);
    return reinterpret_cast<jlong>(edgelm::create_batched(m, (int)pool, (int)nctx));
#else
    (void)modelHandle; (void)pool; (void)nctx; return 0;
#endif
}

JNIEXPORT jboolean JNICALL
Java_ai_edgelm_service_NativeBridge_batchedSubmit(JNIEnv* env, jobject, jlong rtHandle,
                                                  jint uid, jstring jsid, jstring jprompt, jobject jsink) {
#ifdef EDGELM_BATCHED
    auto* rt = reinterpret_cast<edgelm::BatchedRuntime*>(rtHandle);
    if (!rt || !jsink) return JNI_FALSE;
    std::string sid    = jstring_to_utf8(env, jsid);
    std::string prompt = jstring_to_utf8(env, jprompt);

    jobject   gsink   = env->NewGlobalRef(jsink);
    jclass    cls     = env->GetObjectClass(jsink);
    jmethodID onChunk = env->GetMethodID(cls, "onChunk", "(Ljava/lang/String;)V");
    jmethodID onDone  = env->GetMethodID(cls, "onDone", "(I)V");
    jmethodID isCanc  = env->GetMethodID(cls, "isCancelled", "()Z");

    // These fire from the driver thread (batchedStep). Attach it to the JVM to get an env.
    auto get_env = []() -> JNIEnv* {
        JNIEnv* e = nullptr;
        return (g_vm->AttachCurrentThread(&e, nullptr) == JNI_OK) ? e : nullptr;
    };

    edgelm::Sink s;
    s.emit_chunk = [gsink, onChunk, get_env](const std::string& t) {
        JNIEnv* e = get_env(); if (!e) return;
        jstring js = utf8_to_jstring(e, t);
        if (js) { e->CallVoidMethod(gsink, onChunk, js); e->DeleteLocalRef(js); }
    };
    s.is_cancelled = [gsink, isCanc, get_env]() -> bool {
        JNIEnv* e = get_env(); if (!e) return false;
        return e->CallBooleanMethod(gsink, isCanc) == JNI_TRUE;
    };
    s.on_done = [gsink, onDone, get_env](int n) {
        JNIEnv* e = get_env(); if (!e) return;
        e->CallVoidMethod(gsink, onDone, (jint)n);
        e->DeleteGlobalRef(gsink);                  // last use of the sink — free the global ref
    };

    const bool ok = rt->submit((uint32_t)uid, sid, prompt, s);
    if (!ok) env->DeleteGlobalRef(gsink);           // not queued → free now (lambdas won't run)
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    (void)env; (void)rtHandle; (void)uid; (void)jsid; (void)jprompt; (void)jsink; return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_ai_edgelm_service_NativeBridge_batchedStep(JNIEnv*, jobject, jlong rtHandle) {
#ifdef EDGELM_BATCHED
    auto* rt = reinterpret_cast<edgelm::BatchedRuntime*>(rtHandle);
    return rt ? rt->step() : 0;
#else
    (void)rtHandle; return 0;
#endif
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_batchedPause(JNIEnv* env, jobject, jlong rtHandle,
                                                 jint uid, jstring jsid, jboolean paused) {
#ifdef EDGELM_BATCHED
    auto* rt = reinterpret_cast<edgelm::BatchedRuntime*>(rtHandle);
    if (rt) rt->set_paused((uint32_t)uid, jstring_to_utf8(env, jsid), paused == JNI_TRUE);
#else
    (void)env; (void)rtHandle; (void)uid; (void)jsid; (void)paused;
#endif
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_batchedCancel(JNIEnv* env, jobject, jlong rtHandle,
                                                  jint uid, jstring jsid) {
#ifdef EDGELM_BATCHED
    auto* rt = reinterpret_cast<edgelm::BatchedRuntime*>(rtHandle);
    if (rt) rt->cancel((uint32_t)uid, jstring_to_utf8(env, jsid));
#else
    (void)env; (void)rtHandle; (void)uid; (void)jsid;
#endif
}

JNIEXPORT void JNICALL
Java_ai_edgelm_service_NativeBridge_batchedDestroy(JNIEnv*, jobject, jlong rtHandle) {
#ifdef EDGELM_BATCHED
    delete reinterpret_cast<edgelm::BatchedRuntime*>(rtHandle);
#else
    (void)rtHandle;
#endif
}

} // extern "C"
