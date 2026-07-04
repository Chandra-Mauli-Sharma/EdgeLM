package ai.edgelm.demo.b

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
 * Demo B — a SECOND, independent app (distinct UID) binding the SAME runtime.
 * With both A and B attached, the model weights must still be counted once.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeLM.initialize(this)

        val out = TextView(this).apply { textSize = 15f; setPadding(24, 24, 24, 24) }
        val status = TextView(this).apply { textSize = 13f; setPadding(24, 8, 24, 8) }
        val button = Button(this).apply { text = "Generate (Demo B)" }

        button.setOnClickListener {
            out.text = ""; status.text = "streaming…"
            var tokens = 0
            var firstNs = 0L
            lifecycleScope.launch {
                try {
                    // BACKGROUND priority + a long prompt: a foreground request from
                    // Demo A is admitted ahead of this when both are queued.
                    EdgeLM.chat(
                        "default",
                        "Write a long, detailed explanation of how threads work, with examples.",
                        priority = EdgeLM.BACKGROUND,
                    )
                        .collect { chunk ->
                            if (firstNs == 0L) firstNs = System.nanoTime()
                            out.append(chunk); tokens++
                        }
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
