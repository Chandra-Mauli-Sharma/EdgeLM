package ai.edgelm.service

import android.content.Context
import android.util.Log
import ai.edgelm.runtime.ModelStore
import kotlin.concurrent.thread

/**
 * On-device smoke test for the continuous-batching engine (increment 2,
 * docs/PHASE1-KV-POOLING.md). Loads the active model, submits two prompts on two
 * sequences, and drives ONE batched decode loop — proving concurrent multi-sequence
 * decode. Watch logcat tag "edgelm-batched-test": tokens for seq 0 and seq 1 should
 * INTERLEAVE (a token for each per step), not run one prompt fully then the other.
 *
 * Only meaningful when the native lib is built with -DEDGELM_BATCHED=ON; otherwise
 * NativeBridge.batchedRunTest returns 0 and this logs "engine not built in".
 *
 * Invoke from anywhere off the main thread, e.g. a debug button, or via adb:
 *   adb shell am broadcast ... (wire a receiver) — or just call BatchedTest.run(ctx).
 * It spawns its own worker thread, so it's safe to call from the UI thread.
 */
object BatchedTest {

    private const val TAG = "edgelm-batched-test"

    val DEFAULT_PROMPTS = arrayOf(
        "Explain what an octopus is in one sentence.",
        "List three primary colors.",
    )

    /** Fire-and-forget: runs on a worker thread, streams results to logcat. */
    fun run(context: Context, prompts: Array<String> = DEFAULT_PROMPTS) {
        val path = ModelStore.activePath(context)
        if (path.isEmpty()) { Log.w(TAG, "no active model installed — download one first"); return }

        thread(name = "edgelm-batched-test") {
            val handle = NativeBridge.loadModel(path)
            if (handle == 0L) { Log.e(TAG, "loadModel failed: $path"); return@thread }
            try {
                val buffers = Array(prompts.size) { StringBuilder() }
                val sink = object : NativeBridge.BatchedSink {
                    override fun onChunk(seq: Int, text: String) {
                        if (seq in buffers.indices) buffers[seq].append(text)
                        // Per-chunk log makes the interleaving visible in logcat.
                        Log.i(TAG, "seq=$seq += ${text.replace("\n", "\\n")}")
                    }
                }
                val started = System.currentTimeMillis()
                val n = NativeBridge.batchedRunTest(handle, prompts, sink)
                val ms = System.currentTimeMillis() - started
                if (n == 0) {
                    Log.w(TAG, "batchedRunTest returned 0 — native lib built WITHOUT -DEDGELM_BATCHED?")
                } else {
                    Log.i(TAG, "=== batched test done: $n sequences in ${ms}ms ===")
                    buffers.forEachIndexed { i, b -> Log.i(TAG, "[seq $i] result: ${b.toString().trim()}") }
                }
            } finally {
                NativeBridge.unloadModel(handle)
            }
        }
    }
}
