package com.example.helloedgelm

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.edgelm.EdgeLM
import ai.edgelm.EdgeLMUnavailableException
import kotlinx.coroutines.launch

/**
 * The smallest useful EdgeLM app: type a prompt, stream the answer on-device.
 *
 * The entire integration is three calls:
 *   1. EdgeLM.status(context)      — is the shared runtime installed?
 *   2. EdgeLM.initialize(context)  — bind to it (idempotent)
 *   3. EdgeLM.chat(...).collect { } — stream tokens
 *
 * No Binder, no model files, no network code. The heavy lifting (mmap'd weights,
 * llama.cpp, memory sharing across apps) lives in the EdgeLM Runtime app, which the
 * user installs once from Google Play.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val output = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32) }
        val input = EditText(this).apply {
            hint = "Ask something…"
            setText("Explain on-device AI in one sentence")
        }
        val send = Button(this).apply { text = "Send" }

        // Check for the runtime up front so we can guide the user instead of failing.
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

            // Runtime could have been uninstalled since launch — re-check, then bind.
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
                        model = "default",         // serves whatever model is active in the runtime
                        prompt = prompt,
                        sessionId = "hello",       // stable id ⇒ multi-turn memory across sends
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

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(24, 48, 24, 24)
            addView(input)
            addView(send)
            addView(ScrollView(this@MainActivity).apply { addView(output) })
        })
    }
}
