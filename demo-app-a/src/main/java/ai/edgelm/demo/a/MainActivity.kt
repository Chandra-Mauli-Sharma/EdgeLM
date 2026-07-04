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

        // Multi-turn demo: same sessionId across taps => KV is reused, so the model
        // remembers earlier turns WITHOUT us resending them (see PHASE1-KV-POOLING).
        val session = "demo-a-chat"
        var turn = 0
        button.setOnClickListener {
            turn++
            // Turn 1 states a fact; turn 2 asks the model to recall it. If session
            // KV reuse works, turn 2 answers correctly though we never resend turn 1.
            val prompt = if (turn == 1)
                "Remember: my favorite color is green. Reply with just 'ok'."
            else
                "What is my favorite color? Answer in one short sentence."

            out.text = ""; status.text = "streaming… (turn $turn)"
            var tokens = 0
            var firstNs = 0L
            lifecycleScope.launch {
                try {
                    EdgeLM.chat("default", prompt, session, EdgeLM.FOREGROUND)
                        .collect { chunk ->
                            if (firstNs == 0L) firstNs = System.nanoTime()
                            out.append(chunk); tokens++
                        }
                    val decodeS = if (firstNs > 0L) (System.nanoTime() - firstNs) / 1e9 else 0.0
                    val tps = if (tokens > 1 && decodeS > 0) (tokens - 1) / decodeS else 0.0
                    status.text = "turn %d · %d tok · %.1f tok/s".format(turn, tokens, tps)
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
