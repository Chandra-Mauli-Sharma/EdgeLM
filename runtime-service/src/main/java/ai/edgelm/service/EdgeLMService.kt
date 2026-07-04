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

    // Serializes the single engine across the Binder and HTTP front doors.
    private val inferenceLock = Any()

    // Single worker => Binder requests run one at a time. Simple for the spike.
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var http: EdgeLMHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        modelHandle = NativeBridge.loadModel(modelPath)
        Log.i(TAG, "loadModel($modelPath) -> handle=$modelHandle")
        startHttpShim()
    }

    /** The one place inference actually happens; both front doors call this. */
    private fun runInference(
        prompt: String,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): EdgeLMHttpServer.GenStats = synchronized(inferenceLock) {
        if (modelHandle == 0L) return EdgeLMHttpServer.GenStats(0, 0)
        val started = System.currentTimeMillis()
        val sink = object : NativeBridge.TokenSink {
            override fun onChunk(text: String) = onToken(text)
            override fun isCancelled(): Boolean = isCancelled()
        }
        val n = NativeBridge.generate(modelHandle, prompt, sink)
        EdgeLMHttpServer.GenStats(n, System.currentTimeMillis() - started)
    }

    private fun warmModels(): List<String> =
        if (modelHandle != 0L) listOf("model.gguf") else emptyList()

    private fun startHttpShim() {
        http = EdgeLMHttpServer(
            port = HTTP_PORT,
            infer = { _, prompt, onToken, isCancelled -> runInference(prompt, onToken, isCancelled) },
            warmModels = { warmModels() },
        ).also { server ->
            runCatching { server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onSuccess { Log.i(TAG, "HTTP shim on http://127.0.0.1:$HTTP_PORT/v1") }
                .onFailure { e -> Log.e(TAG, "HTTP shim failed to start", e) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEdgeLMService.Stub() {

        override fun submit(model: String, prompt: String, callback: ITokenCallback): Long {
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
                        prompt,
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
