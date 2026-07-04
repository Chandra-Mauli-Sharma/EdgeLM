package ai.edgelm.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Priority-ordered admission scheduler for the shared engine (arch doc Part 8).
 *
 * The runtime has one warm llama_context, so exactly one generation runs at a
 * time. This scheduler decides WHICH waiting request runs next when the engine
 * frees: highest effective priority wins, and a waiting job's priority rises with
 * age so background work can't be starved by a stream of foreground requests.
 *
 * This is **non-preemptive**: a running generation finishes before the next is
 * admitted. True token-boundary preemption (pause/resume mid-generation) needs
 * multiple KV sequences decoding concurrently — the continuous-batching upgrade
 * described in PHASE1-SCHEDULER.md. Non-preemptive is honest and correct for the
 * single-context spike; generations are short (seconds), so head-of-line blocking
 * is bounded.
 */
class AIScheduler(private val agingPerSec: Int = 150) {

    enum class Priority(val base: Int) {
        FOREGROUND(1000), INTERACTIVE(700), BATCH(300), BACKGROUND(100);
        companion object {
            /** Map the API's int priority (0..3) to a class; default INTERACTIVE. */
            fun of(v: Int) = when (v) {
                3 -> FOREGROUND; 2 -> INTERACTIVE; 1 -> BATCH; 0 -> BACKGROUND; else -> INTERACTIVE
            }
        }
    }

    private class Waiter(val priority: Priority, val sinceNanos: Long, val gate: CountDownLatch)

    private val lock = ReentrantLock()
    private val waiters = ArrayList<Waiter>()
    private var busy = false

    /** Run [block] on the engine, admitted in priority order. Blocks until it's this job's turn. */
    fun <T> withEngine(priority: Priority, block: () -> T): T {
        val w = Waiter(priority, System.nanoTime(), CountDownLatch(1))
        lock.withLock { waiters.add(w); dispatch() }
        w.gate.await()                     // wait until scheduled
        try { return block() } finally { lock.withLock { busy = false; dispatch() } }
    }

    /** Pick the highest effective-priority waiter and start it, if the engine is free. */
    private fun dispatch() {
        if (busy || waiters.isEmpty()) return
        val now = System.nanoTime()
        val next = waiters.maxByOrNull { effective(it, now) }!!
        waiters.remove(next)
        busy = true
        next.gate.countDown()
    }

    private fun effective(w: Waiter, now: Long): Int {
        val waitedSec = (now - w.sinceNanos) / 1_000_000_000.0
        return w.priority.base + (waitedSec * agingPerSec).toInt()   // aging prevents starvation
    }
}
