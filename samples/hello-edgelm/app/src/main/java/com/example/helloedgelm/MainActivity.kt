package com.example.helloedgelm

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ai.edgelm.EdgeLM
import ai.edgelm.EdgeLMUnavailableException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // explicit, and lets us handle insets ourselves below

        val output = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32) }
        val input = EditText(this).apply {
            hint = "Ask something…"
            setText("Explain on-device AI in one sentence")
        }
        val send = Button(this).apply { text = "Send" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(input)
            addView(send)
            addView(ScrollView(this@MainActivity).apply { addView(output) })
        }

        // Push content below the status bar and above the nav bar / keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left + 24, top = bars.top + 24, right = bars.right + 24, bottom = bars.bottom + 24)
            insets
        }

        when (EdgeLM.status(this)) {
            EdgeLM.Status.NOT_INSTALLED -> {
                output.text = "EdgeLM Runtime isn't installed — opening Google Play so you can add it once."
                EdgeLM.promptInstall(this)
            }
            EdgeLM.Status.AVAILABLE -> {
                EdgeLM.initialize(this)
                output.text = "Ready. Type a prompt and hit Send."
            }
        }

        send.setOnClickListener {
            val prompt = input.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener
            if (EdgeLM.status(this) == EdgeLM.Status.NOT_INSTALLED) {
                EdgeLM.promptInstall(this)
                return@setOnClickListener
            }
            EdgeLM.initialize(this)
            output.text = ""
            send.isEnabled = false
            lifecycleScope.launch {
                try {
                    EdgeLM.chat(
                        model = "default",
                        prompt = prompt,
                        sessionId = "hello",
                        priority = EdgeLM.FOREGROUND,
                    ).collect { token -> output.append(token) }
                } catch (e: EdgeLMUnavailableException) {
                    output.text = "Runtime unavailable: ${e.message}"
                } catch (t: Throwable) {
                    output.text = "Error: ${t.message}"
                } finally {
                    send.isEnabled = true
                }
            }
        }

        setContentView(root)
    }
}