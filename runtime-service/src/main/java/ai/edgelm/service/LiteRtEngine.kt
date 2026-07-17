package ai.edgelm.service

import android.content.Context
import android.util.Log
import ai.edgelm.runtime.ModelSpec
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LiteRT-LM (GPU) engine — Phase B. Runs `.litertlm` models on LiteRT-LM's portable GPU (OpenCL)
 * backend via the official Kotlin AAR (`com.google.ai.edge.litertlm:litertlm-android`). Generic,
 * not device-specific: GPU/OpenCL only, no per-SoC / NPU-vendor code.
 *
 * ⚠️ FIRST CUT — written against the DOCUMENTED Kotlin API
 * (https://developers.google.com/edge/litert-lm/android). It has not been compiled against the
 * resolved AAR. When you build on-device, adjust the spots marked "VERIFY": the exact streamed
 * message type / text accessor, token counting, and cancellation are the most likely to differ
 * from this in the shipped API version. If it won't compile, the quickest revert is
 * `git checkout` this file + build.gradle.kts + ModelCatalog.kt to the stub state.
 */
@OptIn(ExperimentalApi::class)
class LiteRtEngine(private val appContext: Context? = null) : InferenceEngine {

    private companion object { const val TAG = "LiteRtEngine" }

    override val id: String = "litert-lm"

    /** One loaded Engine plus its per-sessionId Conversations (multi-turn KV reuse). */
    private class LiteRtSession(val engine: Engine) : InferenceEngine.Session {
        val conversations = ConcurrentHashMap<String, Conversation>()
        fun conversation(sessionId: String): Conversation =
            conversations.getOrPut(sessionId.ifEmpty { "default" }) { engine.createConversation() }
    }

    override fun supports(spec: ModelSpec): Boolean = spec.litertUrl != null

    // Needs arm64 (LiteRT libs are 64-bit), then decide by the learned per-device verdict; if not
    // yet known, fall back to the pre-download allowlist so we only ATTEMPT LiteRT on GPU families
    // where it tends to work (Adreno) — this avoids fetching a big .litertlm on a Mali/Exynos
    // device only to fail. LiteRT is GPU-only-worthwhile, hence a false verdict disables it.
    override fun canRunOn(device: DeviceProfile): Boolean {
        if (!device.has64BitAbi) return false
        val ctx = appContext ?: return true   // no context (e.g. unit tests) → optimistic
        return when (EngineProfile.litertGpuUsable(ctx)) {
            true  -> true                            // proven-good on this device
            false -> false                           // proven-bad on this device
            null  -> device.likelyLiteRtGpuCapable   // unknown → only attempt on allowlisted GPUs
        }
    }

    override fun artifactFor(spec: ModelSpec): ModelArtifact? =
        spec.litertUrl?.let { ModelArtifact(it, "litertlm") }

    override fun load(path: String): InferenceEngine.Session? {
        val ctx = appContext ?: run { Log.e(TAG, "no app context — cannot init LiteRT"); return null }
        // GPU-only: LiteRT-CPU is slower than our tuned llama.cpp, so there's no reason to run it.
        // This load IS the capability probe — its result is cached in EngineProfile so routing
        // stops picking LiteRT on devices whose GPU/OpenCL delegate can't build the model (common
        // on Mali/Exynos: initialize() throws "embedding_lookup != nullptr"). On failure we return
        // null and EngineRegistry falls back to llama.cpp.
        return try {
            // MTP (multi-token prediction / speculative decoding) is recommended on GPU.
            ExperimentalFlags.enableSpeculativeDecoding = true
            val engine = Engine(EngineConfig(
                modelPath = path,
                backend = Backend.GPU(),
                cacheDir = ctx.cacheDir.path,   // speeds up 2nd load
            ))
            engine.initialize()   // slow (~up to 10s) — caller already runs off the main thread
            EngineProfile.setLitertGpuUsable(ctx, true)    // probe verdict: GPU works here
            Log.i(TAG, "LiteRT-LM engine initialized (GPU) for $path")
            LiteRtSession(engine)
        } catch (t: Throwable) {
            EngineProfile.setLitertGpuUsable(ctx, false)   // probe verdict: GPU unusable here
            Log.e(TAG, "LiteRT-LM GPU init failed — device marked LiteRT-GPU-unusable; using llama.cpp", t)
            null
        }
    }

    // "<engine> · <backend>", matching LlamaCppEngine's format so the UI reads consistently.
    override fun label(session: InferenceEngine.Session): String = "LiteRT-LM · GPU"

    // MTP is enabled via EngineConfig, not a separate draft model — nothing to attach.
    override fun attachDraft(session: InferenceEngine.Session, draftPath: String): Boolean = false

    override fun generate(
        session: InferenceEngine.Session,
        sessionId: String,
        prompt: String,
        sink: InferenceEngine.TokenSink,
    ): Int {
        val convo = (session as LiteRtSession).conversation(sessionId)
        var produced = 0     // streamed chunks (may be per-token OR one/few large chunks)
        var charCount = 0    // total output chars → token estimate when the API isn't per-token
        val tStart = System.nanoTime()
        var tFirst = 0L                       // time-to-first-chunk ≈ prefill boundary (latch = barrier)
        val done = CountDownLatch(1)
        // Callback streaming (no coroutine/runBlocking bridge — that could deadlock the worker
        // thread against LiteRT's own emit thread). onMessage fires per streamed chunk; onDone
        // releases the latch. VERIFY: whether each `Message` is an incremental delta (assumed) or
        // cumulative, and the exact text accessor (`.toString()` vs e.g. `msg.text`).
        convo.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                if (sink.isCancelled()) return
                val piece = message.toString()
                if (piece.isNotEmpty()) {
                    if (tFirst == 0L) tFirst = System.nanoTime()
                    sink.onChunk(piece); produced++; charCount += piece.length
                }
            }
            override fun onDone() = done.countDown()
            override fun onError(throwable: Throwable) {
                Log.e(TAG, "generation error", throwable); done.countDown()
            }
        })
        // Block this worker thread until streaming completes (with a safety timeout).
        if (!done.await(5, TimeUnit.MINUTES)) Log.w(TAG, "generation timed out after 5 min")
        // Report DECODE tok/s (excludes prefill / first-token latency) alongside overall, so short
        // replies aren't dragged down by prompt processing. Output streams per-token, so `produced`
        // is a good token count (chars/4 is a fallback if a build delivers big chunks).
        val tEnd = System.nanoTime()
        val totalS = (tEnd - tStart) / 1e9
        val tokens = maxOf(produced, charCount / 4)
        val prefillS = if (tFirst > 0L) (tFirst - tStart) / 1e9 else 0.0
        val decodeS = if (tFirst > 0L) (tEnd - tFirst) / 1e9 else totalS
        val decodeToks = (tokens - 1).coerceAtLeast(0)
        val decodeTps = if (decodeS > 0) decodeToks / decodeS else 0.0
        val overallTps = if (totalS > 0) tokens / totalS else 0.0
        Log.i(TAG, "litert perf: %d tok | prefill %.2fs | decode %d tok in %.2fs = %.1f tok/s (overall %.1f) [%s]"
            .format(tokens, prefillS, decodeToks, decodeS, decodeTps, overallTps, label(session)))
        return tokens
    }

    // Cooperative cancel: generate()'s collect loop stops on sink.isCancelled() at the next chunk.
    override fun cancel(session: InferenceEngine.Session) {}

    override fun unload(session: InferenceEngine.Session) {
        val s = session as LiteRtSession
        s.conversations.values.forEach { runCatching { it.close() } }
        s.conversations.clear()
        runCatching { s.engine.close() }
    }
}
