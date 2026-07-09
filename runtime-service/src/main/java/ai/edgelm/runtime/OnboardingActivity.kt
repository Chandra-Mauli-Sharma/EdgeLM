package ai.edgelm.runtime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * First-run welcome. Explains, in plain language, what EdgeLM is and why it's
 * different (private, offline, shared), then hands off to the main screen. Shown
 * once — [Prefs.setOnboarded] is set on "Get started".
 */
class OnboardingActivity : ComponentActivity() {

    private val bg = Color.parseColor("#0B0E10")
    private val mist = Color.parseColor("#EEF2F0")
    private val fog = Color.parseColor("#B4C0C5")
    private val green = Color.parseColor("#9BFF3C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(28), dp(64), dp(28), dp(36)); gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112))
        })
        root.addView(text("Welcome to EdgeLM", 27f, mist, true, Gravity.CENTER).apply { setPadding(0, dp(20), 0, 0) })
        root.addView(text("AI that runs on your phone", 16f, green, false, Gravity.CENTER).apply { setPadding(0, dp(6), 0, dp(8)) })

        val points = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) }
        }
        points.addView(feature("🔒", "Private", "Your messages never leave your phone. No cloud, no accounts, no tracking."))
        points.addView(feature("✈️", "Works offline", "Once a model is downloaded, it runs with no internet at all."))
        points.addView(feature("⚡", "Shared by every app", "Set it up once and every app can use it — saving space and memory."))
        root.addView(points)

        root.addView(Button(this).apply {
            text = "Get started"; isAllCaps = false
            setTypeface(typeface, Typeface.BOLD); textSize = 16f
            setTextColor(Color.parseColor("#0B0E10")); background = pill(); stateListAnimator = null
            setPadding(dp(24), dp(15), dp(24), dp(15))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(34) }
            setOnClickListener {
                Prefs.setOnboarded(this@OnboardingActivity)
                finish()
            }
        })
        root.addView(text("You'll pick an AI on the next screen.", 12.5f, Color.parseColor("#84939B"), false, Gravity.CENTER)
            .apply { setPadding(0, dp(14), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(28) + b.left, dp(24) + b.top, dp(28) + b.right, dp(24) + b.bottom)
            insets
        }
    }

    private fun feature(emoji: String, title: String, body: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        row.addView(TextView(this).apply {
            text = emoji; textSize = 24f
            layoutParams = LinearLayout.LayoutParams(dp(44), WRAP_CONTENT)
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(text(title, 16.5f, mist, true, Gravity.START))
        col.addView(text(body, 13.5f, fog, false, Gravity.START).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col)
        return row
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean, grav: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = grav
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun pill(): StateListDrawable {
        fun d(c: String) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor(c)) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), d("#7CEB1E"))
            addState(intArrayOf(), d("#9BFF3C"))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
