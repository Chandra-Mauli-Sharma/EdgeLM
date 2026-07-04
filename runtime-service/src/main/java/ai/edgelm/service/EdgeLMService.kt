package ai.edgelm.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ai.edgelm.contract.IEdgeLMService
import ai.edgelm.contract.ITokenCallback
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The shared EdgeLM runtime.
 *
 * One instance, one process (:core), one model mmap'd once and shared across
 * every bound app. Two front doors sit on top of a single inference path:
 *   - Binder (AIDL) for the native SDK — carries the caller UID.
 *   - A localhost OpenAI-compatible HTTP shim (127.0.0.1:1408) for cURL / OpenAI
 *     SDKs / external tools (see docs/PHASE1-OPENAI-HTTP-SHIM.md).
 *
 * Both paths serialize on [inferenceLock] — one engine, one turn at a time. The
 * real priority scheduler (docs/PHASE1-SCHEDULER.md) replaces that later.
 */
class EdgeLMService : Service() {

    private companion object {
        const val TAG = "EdgeLMService"
        const val HTTP_PORT = 1408
    }

    // Phase 0 ships exactly one model. Push it to this path with adb (see plan).
    private val modelPath by lazy { File(filesDir, "model.gguf").absolutePath }

    @Volatile private var modelHandle: Long = 0L
    private val requestIds = AtomicLong(1)
    private val cancelled = ConcurrentHashMap<Long, AtomicBoolean>()

    // Priority-ordered admission to the single engine (Part 8). Also provides the
    // mutual exclusion the shared context needs — only one generation runs at a time.
    private val scheduler = AIScheduler()

    // Pool so multiple Binder requests can be *waiting* in the scheduler at once;
    // the scheduler (not this pool) serializes actual execution in priority order.
    private val worker = Executors.newCachedThreadPool()

    @Volatile private var http: EdgeLMHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        modelHandle = NativeBridge.loadModel(modelPath)
        Log.i(TAG, "loadModel($modelPath) -> handle=$modelHandle")
        startHttpShim()
    }

    /** The one place inference actually happens; both front doors call this. */
    private fun runInference(
        sessionId: String,
        prompt: String,
        priority: AIScheduler.Priority,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): EdgeLMHttpServer.GenStats = scheduler.withEngine(priority) {
        // Single shared context => serialize; the scheduler admits the highest
        // priority waiter next (with aging). One generation runs at a time.
        if (modelHandle == 0L) return@withEngine EdgeLMHttpServer.GenStats(0, 0)
        val started = System.currentTimeMillis()
        val sink = object : NativeBridge.TokenSink {
            override fun onChunk(text: String) = onToken(text)
            override fun isCancelled(): Boolean = isCancelled()
        }
        val n = NativeBridge.generate(modelHandle, sessionId, prompt, sink)
        EdgeLMHttpServer.GenStats(n, System.currentTimeMillis() - started)
    }

    private fun warmModels(): List<String> =
        if (modelHandle != 0L) listOf("model.gguf") else emptyList()

    private fun startHttpShim() {
        http = EdgeLMHttpServer(
            port = HTTP_PORT,
            // HTTP path is stateless (OpenAI clients resend full history) -> no session,
            // and defaults to BATCH priority (no UI foreground signal).
            infer = { _, prompt, onToken, isCancelled ->
                runInference("", prompt, AIScheduler.Priority.BATCH, onToken, isCancelled)
            },
            warmModels = { warmModels() },
        ).also { server ->
            runCatching { server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onSuccess { Log.i(TAG, "HTTP shim on http://127.0.0.1:$HTTP_PORT/v1") }
                .onFailure { e -> Log.e(TAG, "HTTP shim failed to start", e) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEdgeLMService.Stub() {

        override fun submit(model: String, sessionId: String, prompt: String,
                            priority: Int, callback: ITokenCallback): Long {
            val id = requestIds.getAndIncrement()
            val cancelFlag = AtomicBoolean(false)
            cancelled[id] = cancelFlag

            worker.execute {
                if (modelHandle == 0L) {
                    callback.onError("model not loaded — push model.gguf (see PHASE-0-BUILD-PLAN.md)")
                    cancelled.remove(id); return@execute
                }
                try {
                    val stats = runInference(
                        sessionId,
                        prompt,
                        AIScheduler.Priority.of(priority),
                        onToken = { runCatching { callback.onTokens(it) } },
                        isCancelled = { cancelFlag.get() },
                    )
                    callback.onDone(stats.tokenCount, stats.elapsedMs)
                } catch (t: Throwable) {
                    runCatching { callback.onError(t.message ?: "generation failed") }
                } finally {
                    cancelled.remove(id)
                }
            }
            return id
        }

        override fun cancel(requestId: Long) {
            cancelled[requestId]?.set(true)
            if (modelHandle != 0L) NativeBridge.cancel(modelHandle)
        }

        override fun warmModels(): Array<String> = this@EdgeLMService.warmModels().toTypedArray()
    }

    override fun onDestroy() {
        runCatching { http?.stop() }
        worker.shutdownNow()
        if (modelHandle != 0L) NativeBridge.unloadModel(modelHandle)
        super.onDestroy()
    }
}
