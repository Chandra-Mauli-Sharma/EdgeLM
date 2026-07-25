package ai.edgelm.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Weighted-fair, governed admission scheduler for the shared engine (arch doc Part 8).
 *
 * The runtime has one warm llama_context, so exactly one generation runs at a time.
 * This scheduler decides WHICH waiting request runs next when the engine frees. Its
 * effective-priority score combines three things:
 *
 *   1. **Priority class** — foreground > interactive > batch > background (the base).
 *   2. **Aging** — a waiter's score rises with wait time, so nothing starves.
 *   3. **Per-app weighted fairness** — an app that has recently consumed a lot of
 *      engine time is penalised, so two apps at the same priority get a fair share
 *      instead of a first-come stampede from one of them. Usage decays over time.
 *
 * On top of that, an optional **governor** (battery/thermal) can defer or block
 * non-interactive work under heat or low battery — the hard clamp from Part 8.
 *
 * **Preemption**: execution is still non-preemptive — a running generation finishes
 * before the next is admitted. True token-boundary pause/resume needs multiple KV
 * sequences decoding concurrently (the continuous-batching upgrade in
 * PHASE1-KV-POOLING.md). Until then, the scheduler tracks the running job's priority
 * and exposes a cooperative [Preemption] signal that a *preemptible* block can poll;
 * the engine may honor it later to yield a background decode to a foreground request.
 * Generations are short (seconds), so head-of-line blocking is bounded meanwhile.
 */
class AIScheduler(
    private val agingPerSec: Int = 150,
    private val fairnessWeight: Int = 400,        // how hard recent hogs are penalised
    private val usageHalfLifeSec: Double = 20.0,  // how fast per-app usage decays
    private val governor: (() -> DeviceGovernor.State)? = null,
) {

    enum class Priority(val base: Int) {
        FOREGROUND(1000), INTERACTIVE(700), BATCH(300), BACKGROUND(100);
        val isBackground: Boolean get() = this == BATCH || this == BACKGROUND
        companion object {
            /** Map the API's int priority (0..3) to a class; default INTERACTIVE. */
            fun of(v: Int) = when (v) {
                3 -> FOREGROUND; 2 -> INTERACTIVE; 1 -> BATCH; 0 -> BACKGROUND; else -> INTERACTIVE
            }
        }
    }

    /** Cooperative yield signal handed to a preemptible job (see class doc). */
    class Preemption internal constructor(private val flag: AtomicBoolean) {
        /** True once a strictly-higher-priority request is waiting behind this job. */
        fun shouldYield(): Boolean = flag.get()
    }

    private class Waiter(
        val priority: Priority,
        val appId: String,
        val sinceNanos: Long,
        val gate: CountDownLatch,
        val preemptFlag: AtomicBoolean = AtomicBoolean(false),
    )

    /** Decaying record of how much engine time an app has recently used. */
    private class Usage(var nanos: Double, var lastUpdateNanos: Long)

    private val lock = ReentrantLock()
    private val waiters = ArrayList<Waiter>()
    private var busy = false
    @Volatile private var runningPriority: Priority? = null
    @Volatile private var running: Waiter? = null
    private val usageByApp = ConcurrentHashMap<String, Usage>()

    /** Back-compat entry point (system/internal callers): no app identity, non-preemptible. */
    fun <T> withEngine(priority: Priority, block: () -> T): T =
        withEngine(priority, appId = "", block = { block() })

    /**
     * Run [block] on the engine, admitted in weighted-fair priority order. Blocks until
     * it's this job's turn. [appId] attributes engine time for fairness (use the caller's
     * package/uid; "" for internal work). [block] receives a [Preemption] it may poll.
     */
    fun <T> withEngine(priority: Priority, appId: String, block: (Preemption) -> T): T {
        // Governor clamp: a severe-thermal device refuses to admit background work at all.
        governor?.invoke()?.let { st ->
            if (priority.isBackground && st.blockBackground)
                throw DeferredException("deferred: device is too hot for background inference")
        }
        val w = Waiter(priority, appId, System.nanoTime(), CountDownLatch(1))
        lock.withLock { waiters.add(w); dispatch() }
        w.gate.await()                     // wait until scheduled
        val startNanos = System.nanoTime()
        try {
            return block(Preemption(w.preemptFlag))
        } finally {
            val spent = System.nanoTime() - startNanos
            recordUsage(appId, spent)
            lock.withLock { busy = false; runningPriority = null; running = null; dispatch() }
        }
    }

    /** Thrown when the governor refuses to admit a job (caller surfaces it as a soft error). */
    class DeferredException(message: String) : RuntimeException(message)

    /** Pick the highest effective-score waiter and start it, if the engine is free. */
    private fun dispatch() {
        if (busy || waiters.isEmpty()) return
        val now = System.nanoTime()
        val state = governor?.invoke()
        // Under stress, background jobs wait behind ANY interactive/foreground waiter.
        val eligible = waiters.filter { w ->
            !(w.priority.isBackground && state?.deferBackground == true &&
              waiters.any { !it.priority.isBackground })
        }.ifEmpty { waiters }

        val next = eligible.maxByOrNull { effective(it, now) }!!
        waiters.remove(next)
        busy = true
        runningPriority = next.priority
        running = next
        // Signal any still-waiting, strictly-lower-priority background jobs that a
        // higher-priority request is in flight — the cooperative preemption hint.
        waiters.forEach { if (next.priority.base > it.priority.base) it.preemptFlag.set(false) }
        next.gate.countDown()
    }

    private fun effective(w: Waiter, now: Long): Int {
        val waitedSec = (now - w.sinceNanos) / 1_000_000_000.0
        val aging = (waitedSec * agingPerSec).toInt()
        val penalty = (fairnessShare(w.appId, now) * fairnessWeight).toInt()
        return w.priority.base + aging - penalty     // aging prevents starvation; penalty enforces fairness
    }

    /** Recent normalised usage for [appId] in 0..1 (decayed), 0 for anonymous/internal work. */
    private fun fairnessShare(appId: String, now: Long): Double {
        if (appId.isEmpty()) return 0.0
        val u = usageByApp[appId] ?: return 0.0
        val decayed = decay(u.nanos, u.lastUpdateNanos, now)
        // Normalise against a rolling "typical generation" (~2s of engine time).
        return (decayed / 2_000_000_000.0).coerceIn(0.0, 1.0)
    }

    private fun recordUsage(appId: String, spentNanos: Long) {
        if (appId.isEmpty()) return
        val now = System.nanoTime()
        usageByApp.compute(appId) { _, prev ->
            val base = prev?.let { decay(it.nanos, it.lastUpdateNanos, now) } ?: 0.0
            Usage(base + spentNanos, now)
        }
    }

    /** Exponential decay of [nanos] since [lastNanos] with [usageHalfLifeSec] half-life. */
    private fun decay(nanos: Double, lastNanos: Long, now: Long): Double {
        val dtSec = (now - lastNanos) / 1_000_000_000.0
        if (dtSec <= 0) return nanos
        return nanos * Math.pow(0.5, dtSec / usageHalfLifeSec)
    }
}
