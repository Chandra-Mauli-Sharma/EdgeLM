package ai.edgelm.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ai.edgelm.contract.IEdgeLMService
import ai.edgelm.contract.ITokenCallback

/**
 * A minimal chat playground to verify the runtime + active model actually work.
 *
 * Binds the shared runtime directly (the runtime app holds USE_RUNTIME) and streams
 * tokens back via [ITokenCallback], showing throughput and any error verbatim — so a
 * user (or you) can confirm end-to-end inference without needing a separate app.
 */
class PlaygroundActivity : ComponentActivity() {

    private val bg = Color.parseColor("#0B0E10")
    private val carbon = Color.parseColor("#14181B")
    private val mist = Color.parseColor("#EEF2F0")
    private val fog = Color.parseColor("#B4C0C5")
    private val green = Color.parseColor("#9BFF3C")
    private val steel = Color.parseColor("#84939B")
    private val red = Color.parseColor("#FF6B7A")

    private val PRIORITY_FOREGROUND = 3
    private var sessionId = "playground-${System.currentTimeMillis()}"

    private var service: IEdgeLMService? = null
    @Volatile private var generating = false
    @Volatile private var currentRequestId = 0L
    @Volatile private var cancelledByUser = false

    private lateinit var transcript: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var statusLine: TextView

    // The assistant bubble currently being streamed into.
    private var streamingView: TextView? = null
    private val streamBuffer = StringBuilder()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IEdgeLMService.Stub.asInterface(binder); updateStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(18), dp(44), dp(18), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Playground"; textSize = 22f; setTextColor(mist); setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "Clear"; isAllCaps = false; textSize = 13.5f
            setTextColor(steel); background = ghostBg(); stateListAnimator = null
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { clearChat() }
        })
        root.addView(header)
        statusLine = TextView(this).apply {
            textSize = 12.5f; setTextColor(steel); setPadding(0, dp(3), 0, dp(10))
        }
        root.addView(statusLine)

        // Transcript
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            isFillViewport = true
            addView(transcript)
        }
        root.addView(scroller)
        addBubble("Send a prompt to check the runtime is answering. Uses the active model.",
            assistant = true, color = fog)

        // One-tap example prompts for quick testing.
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Say hello 👋",
            "Write a haiku about phones",
            "Explain on-device AI simply",
            "List 3 uses for a local LLM",
            "What's 17 × 23?",
        ).forEach { prompt -> chipRow.addView(chip(prompt)) }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            addView(chipRow)
        })

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        input = EditText(this).apply {
            hint = "Type a message…"; setHintTextColor(steel); setTextColor(mist); textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            background = fieldBg(); setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        sendBtn = Button(this).apply {
            text = "Send"; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); textSize = 15f
            setTextColor(Color.parseColor("#0B0E10")); background = pillBg(); stateListAnimator = null
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) }
            setOnClickListener { if (generating) stopGeneration() else onSend() }
        }
        inputRow.addView(input); inputRow.addView(sendBtn)
        root.addView(inputRow)

        setContentView(root)
        // Pad by system bars, and by the IME so the input rises above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(dp(18) + b.left, dp(16) + b.top, dp(18) + b.right, dp(14) + b.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        val i = Intent().setComponent(ComponentName(packageName, "ai.edgelm.service.EdgeLMService"))
        runCatching { startForegroundService(i) }
        runCatching { bindService(i, conn, Context.BIND_AUTO_CREATE) }
        updateStatus()
    }
    override fun onStop() { super.onStop(); runCatching { unbindService(conn) } }

    private fun updateStatus() {
        val svc = service
        val warm = runCatching { svc?.warmModels()?.firstOrNull() }.getOrNull()
        val active = ModelStore.activeId(this)?.let { ModelCatalog.byId(it)?.name ?: it }
        statusLine.text = when {
            svc == null -> "○ Connecting to runtime…"
            active == null -> "● Connected · no model installed — download one first"
            warm != null -> "● Connected · $warm (warm)"
            else -> "● Connected · $active (loads on first message)"
        }
        statusLine.setTextColor(if (svc != null && active != null) green else if (svc == null) steel else red)
    }

    private fun onSend() {
        if (generating) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val svc = service
        if (svc == null) { toastLine("Runtime not connected yet."); return }

        addBubble(text, assistant = false, color = mist)
        input.setText("")
        // Start an assistant bubble to stream into.
        streamBuffer.setLength(0)
        cancelledByUser = false
        streamingView = addBubble("…", assistant = true, color = mist)
        setGenerating(true)

        val callback = object : ITokenCallback.Stub() {
            override fun onTokens(chunk: String?) {
                if (chunk == null) return
                runOnUiThread {
                    if (streamBuffer.isEmpty()) streamingView?.text = ""
                    streamBuffer.append(chunk)
                    streamingView?.text = streamBuffer.toString()
                    scrollToBottom()
                }
            }
            override fun onDone(tokenCount: Int, elapsedMs: Long) = runOnUiThread {
                val tps = if (elapsedMs > 0) tokenCount * 1000.0 / elapsedMs else 0.0
                if (streamBuffer.isEmpty()) streamingView?.text = "(no output)"
                statusLine.text = if (cancelledByUser)
                    "■ Stopped · $tokenCount tokens · ${"%.1f".format(tps)} tok/s"
                else "✓ $tokenCount tokens · ${"%.1f".format(tps)} tok/s"
                statusLine.setTextColor(green)
                setGenerating(false)
            }
            override fun onError(message: String?) = runOnUiThread {
                streamingView?.text = "⚠ ${message ?: "generation failed"}"
                streamingView?.setTextColor(red)
                statusLine.text = "✗ Error"
                statusLine.setTextColor(red)
                setGenerating(false)
            }
        }

        runCatching {
            currentRequestId = svc.submit("default", sessionId, text, PRIORITY_FOREGROUND, callback)
        }.onFailure {
            streamingView?.text = "⚠ ${it.message}"; streamingView?.setTextColor(red); setGenerating(false)
        }
    }

    private fun stopGeneration() {
        cancelledByUser = true
        runCatching { service?.cancel(currentRequestId) }
        statusLine.text = "Stopping…"; statusLine.setTextColor(steel)
    }

    private fun clearChat() {
        if (generating) stopGeneration()
        transcript.removeAllViews()
        // Fresh session id => runtime starts a new conversation (no carried-over KV).
        sessionId = "playground-${System.currentTimeMillis()}"
        streamingView = null; streamBuffer.setLength(0)
        addBubble("New chat. Send a prompt to test the active model.", assistant = true, color = fog)
        updateStatus()
    }

    private fun setGenerating(on: Boolean) {
        generating = on
        sendBtn.text = if (on) "Stop" else "Send"
        sendBtn.background = if (on) stopBg() else pillBg()
        sendBtn.setTextColor(if (on) red else Color.parseColor("#0B0E10"))
    }

    // ---- Bubbles / helpers ----------------------------------------------------

    private fun addBubble(text: String, assistant: Boolean, color: Int): TextView {
        val bubble = TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(color)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = bubbleBg(assistant)
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
            gravity = if (assistant) Gravity.START else Gravity.END
            marginStart = if (assistant) 0 else dp(48)
            marginEnd = if (assistant) dp(48) else 0
        }
        val wrap = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            gravity = if (assistant) Gravity.START else Gravity.END
            addView(bubble, lp)
        }
        transcript.addView(wrap)
        scrollToBottom()
        return bubble
    }

    /** A tappable example-prompt chip; sends immediately (ignored while generating). */
    private fun chip(prompt: String) = TextView(this).apply {
        text = prompt; textSize = 13f; setTextColor(fog); maxLines = 1
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat(); setColor(carbon); setStroke(dp(1), Color.parseColor("#2E3941"))
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
        layoutParams = lp
        setOnClickListener {
            if (generating) return@setOnClickListener
            input.setText(prompt); onSend()
        }
    }

    private fun scrollToBottom() = scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    private fun toastLine(msg: String) { statusLine.text = msg; statusLine.setTextColor(red) }

    private fun bubbleBg(assistant: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (assistant) carbon else Color.parseColor("#17240D"))
        setStroke(dp(1), Color.parseColor(if (assistant) "#242D33" else "#2C4715"))
    }
    private fun fieldBg() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat(); setColor(carbon); setStroke(dp(1), Color.parseColor("#2E3941"))
    }
    private fun pillBg(): StateListDrawable {
        fun d(c: String) = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor(c)) }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), d("#5A7A2E"))
            addState(intArrayOf(android.R.attr.state_pressed), d("#7CEB1E"))
            addState(intArrayOf(), d("#9BFF3C"))
        }
    }
    // Stop = neutral dark pill with a red-ish outline (green pill means "go").
    private fun stopBg(): StateListDrawable {
        fun d(fill: String) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.parseColor(fill)); setStroke(dp(1), red)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), d("#2A1418"))
            addState(intArrayOf(), d("#1A1013"))
        }
    }
    private fun ghostBg(): StateListDrawable {
        fun d(fill: Int) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(fill); setStroke(dp(1), Color.parseColor("#2E3941"))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), d(Color.parseColor("#1A2024")))
            addState(intArrayOf(), d(Color.TRANSPARENT))
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
