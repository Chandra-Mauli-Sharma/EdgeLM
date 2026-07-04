package ai.edgelm.demo.a

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.edgelm.EdgeLM
import kotlinx.coroutines.launch

/**
 * Demo A — the simplest possible EdgeLM consumer.
 * It never sees Binder, mmap, or llama.cpp: it calls EdgeLM.chat() and streams.
 *
 * Run BOTH demo A and demo B against the running runtime-service, then measure
 * with tools/measure_memory.sh to confirm the weights are shared (Phase 0 gate).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeLM.initialize(this)

        val out = TextView(this).apply { textSize = 15f; setPadding(24, 24, 24, 24) }
        val status = TextView(this).apply { textSize = 13f; setPadding(24, 8, 24, 8) }
        val button = Button(this).apply { text = "Generate (Demo A)" }

        button.setOnClickListener {
            out.text = ""; status.text = "streaming…"
            var tokens = 0
            var firstNs = 0L                       // set when the first token arrives
            lifecycleScope.launch {
                try {
                    EdgeLM.chat("default", "Say hello from a shared local runtime.")
                        .collect { chunk ->
                            if (firstNs == 0L) firstNs = System.nanoTime()  // prefill done
                            out.append(chunk); tokens++
                        }
                    // decode-only rate (excludes cold-start prefill) — matches the
                    // native `perf:` log; the runtime's honest tokens/sec.
                    val decodeS = if (firstNs > 0L) (System.nanoTime() - firstNs) / 1e9 else 0.0
                    val tps = if (tokens > 1 && decodeS > 0) (tokens - 1) / decodeS else 0.0
                    status.text = "done · %d tok · %.1f tok/s (decode)".format(tokens, tps)
                } catch (t: Throwable) {
                    status.text = "error: ${t.message}"
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(button)
            addView(status)
            addView(ScrollView(this@MainActivity).apply { addView(out) })
        })
    }
}
