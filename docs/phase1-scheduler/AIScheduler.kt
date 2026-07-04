package ai.edgelm.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * OS-style inference scheduler (arch doc Part 8), spike-grade.
 *
 * The key insight: the scheduling quantum is ONE TOKEN. Between tokens a job
 * releases its compute permit and re-requests it; permits are handed to the
 * highest effective-priority waiter. So a foreground request preempts a
 * background one within a token or two — without killing the background job
 * (its KV stays resident; it just waits its turn).
 *
 * `concurrency` = how many jobs may decode at once (1 = strict serialize, good
 * for CPU; raise it once you add continuous batching / GPU headroom).
 * `agingPerSec` = how fast a waiting job's effective priority rises, which
 * prevents starvation.
 */
class AIScheduler(
    private val concurrency: Int = 1,
    private val agingPerSec: Int = 50,
    private val maxPerUid: Int = 4,
) {
    enum class Priority(val base: Int) {
        FOREGROUND(1000), INTERACTIVE(700), BATCH(300), BACKGROUND(100)
    }

    class Job(
        val id: Long,
        val uid: Int,
        @Volatile var priority: Priority,
        val submitNanos: Long = System.nanoTime(),
    )

    private val lock = ReentrantLock(/* fair = */ true)
    private val turn = lock.newCondition()
    private val ready = HashSet<Job>()     // want a turn, not currently holding one
    private val holders = HashSet<Job>()   // currently holding a compute permit

    /** Admission control: reject if this app already has too many in flight. */
    fun submit(job: Job): Boolean = lock.withLock {
        val inFlight = ready.count { it.uid == job.uid } + holders.count { it.uid == job.uid }
        if (inFlight >= maxPerUid) return false
        ready.add(job); turn.signalAll(); true
    }

    /** Called between tokens: release the current permit, block until scheduled again. */
    fun yieldTurn(job: Job) = lock.withLock {
        holders.remove(job)
        ready.add(job)
        turn.signalAll()
        while (true) {
            if (holders.size < concurrency && isTopWaiter(job)) {
                ready.remove(job); holders.add(job); return
            }
            // re-evaluate periodically so aging can promote a starved waiter
            turn.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /** Terminal: free this job's permit and wake the next waiter. */
    fun finish(job: Job) = lock.withLock {
        ready.remove(job); holders.remove(job); turn.signalAll()
    }

    private fun isTopWaiter(job: Job): Boolean {
        val eff = effective(job)
        // eligible if no other waiting job outranks it
        return ready.none { it !== job && effective(it) > eff }
    }

    private fun effective(job: Job): Int {
        val waitedSec = (System.nanoTime() - job.submitNanos) / 1_000_000_000.0
        return job.priority.base + (waitedSec * agingPerSec).toInt()
    }
}
