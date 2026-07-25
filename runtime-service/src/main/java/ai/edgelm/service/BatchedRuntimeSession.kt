package ai.edgelm.service

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Service-facing wrapper around the native continuous-batching engine (increment 2,
 * docs/PHASE1-KV-POOLING.md). Owns one persistent [NativeBridge] BatchedRuntime plus a
 * single driver thread that loops `batchedStep()`, so multiple app requests decode
 * CONCURRENTLY instead of serializing through the scheduler lock.
 *
 * Each [generate] call queues a sequence and blocks (from the caller's thread) until that
 * sequence finishes — so the existing service/SDK contract (a blocking generate that
 * streams tokens) is preserved, while under the hood N of them share the context.
 *
 * Preemption (arch doc Part 8): while any foreground/interactive request is active, all
 * background/batch sequences are paused (dropped from the batch, KV intact) so the
 * foreground request gets full decode bandwidth; they resume when it finishes. This is
 * the concrete realization of AIScheduler's Preemption seam via `set_paused`.
 *
 * Opt-in: only works when the native lib is built with -DEDGELM_BATCHED=ON (else the
 * NativeBridge calls are no-ops and [create] returns null).
 */
class BatchedRuntimeSession private constructor(
    private val modelHandle: Long,
    private val rtHandle: Long,
    private val poolSize: Int,
) {
    companion object {
        private const val TAG = "edgelm-batched-svc"

        /** Load [modelPath] (sharing mmap'd weights) and stand up the runtime + driver, or null. */
        fun create(modelPath: String, poolSize: Int = 4, nCtxPerSeq: Int = 512): BatchedRuntimeSession? {
            if (modelPath.isEmpty()) return null
            val mh = NativeBridge.loadModel(modelPath)
            if (mh == 0L) { Log.e(TAG, "loadModel failed"); return null }
            val rt = NativeBridge.batchedCreate(mh, poolSize, nCtxPerSeq)
            if (rt == 0L) { Log.e(TAG, "batchedCreate failed (built without -DEDGELM_BATCHED?)"); NativeBridge.unloadModel(mh); return null }
            return BatchedRuntimeSession(mh, rt, poolSize).also { it.startDriver() }
        }
    }

    private data class Active(val uid: Int, val sessionId: String, val priority: AIScheduler.Priority)

    private val lock = Object()                       // guards [active] + driver parking
    private val active = ArrayList<Active>()          // in-flight sequences (guarded by lock)
    @Volatile private var running = true
    private var driver: Thread? = null
    private val reqSeq = AtomicLong(0)

    private fun startDriver() {
        driver = Thread({
            while (running) {
                val n = NativeBridge.batchedStep(rtHandle)   // one token per active sequence
                if (n == 0) synchronized(lock) {
                    if (running && active.isEmpty()) lock.wait(50)   // park until a submit wakes us
                }
            }
        }, "edgelm-batched-driver").apply { isDaemon = true; start() }
    }

    /**
     * Queue a generation and block until it completes, streaming chunks via [onToken].
     * Concurrency-safe: many callers can be in here at once; the driver decodes them together.
     */
    fun generate(
        uid: Int,
        sessionId: String,
        prompt: String,
        priority: AIScheduler.Priority,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): EdgeLMHttpServer.GenStats {
        // Distinct KV per conversation. Stateless ("") requests get a unique id so two
        // concurrent one-shots don't collide on the same sequence.
        val sid = if (sessionId.isEmpty()) "req-${reqSeq.incrementAndGet()}" else sessionId

        val latch = CountDownLatch(1)
        val startNs = System.nanoTime()
        var firstNs = 0L
        var produced = 0
        val sink = object : NativeBridge.BatchedRequestSink {
            override fun onChunk(text: String) {
                if (firstNs == 0L) firstNs = System.nanoTime()
                onToken(text)
            }
            override fun onDone(tokens: Int) { produced = tokens; latch.countDown() }
            override fun isCancelled(): Boolean = isCancelled()
        }

        val started = System.currentTimeMillis()
        synchronized(lock) {
            while (running) {
                if (NativeBridge.batchedSubmit(rtHandle, uid, sid, prompt, sink)) {
                    active.add(Active(uid, sid, priority))
                    applyPreemptionLocked()
                    lock.notifyAll()                 // wake the driver
                    break
                }
                lock.wait(100)                       // pool full — wait for a slot to free
            }
        }

        latch.await()

        synchronized(lock) {
            active.removeAll { it.uid == uid && it.sessionId == sid }
            applyPreemptionLocked()
            lock.notifyAll()                         // wake any submit waiting on a slot
        }

        val elapsed = System.currentTimeMillis() - started
        val ttft = if (firstNs > 0L) (firstNs - startNs) / 1_000_000L else 0L
        return EdgeLMHttpServer.GenStats(produced, elapsed, ttft)
    }

    /** Pause every background/batch seq while any foreground/interactive seq is active. */
    private fun applyPreemptionLocked() {
        val hasForeground = active.any { !it.priority.isBackground }
        for (a in active) {
            NativeBridge.batchedPause(rtHandle, a.uid, a.sessionId, hasForeground && a.priority.isBackground)
        }
    }

    /** Tear down: cancel in-flight sequences, drain, stop the driver, free native. */
    fun shutdown() {
        synchronized(lock) {
            active.forEach { NativeBridge.batchedCancel(rtHandle, it.uid, it.sessionId) }
            lock.notifyAll()
            // Let the driver retire the cancelled sequences (their onDone frees the latches).
            val deadline = System.currentTimeMillis() + 1500
            while (active.isNotEmpty() && System.currentTimeMillis() < deadline) lock.wait(50)
        }
        running = false
        synchronized(lock) { lock.notifyAll() }
        driver?.join(1000)
        NativeBridge.batchedDestroy(rtHandle)
        NativeBridge.unloadModel(modelHandle)
        Log.i(TAG, "batched session shut down")
    }

    fun activeCount(): Int = synchronized(lock) { active.size }
}
