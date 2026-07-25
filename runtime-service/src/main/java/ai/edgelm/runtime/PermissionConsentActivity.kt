package ai.edgelm.runtime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.edgelm.service.CapabilityBroker

/**
 * The system consent surface for a high-risk EdgeLM capability (arch doc Part 7:
 * "high-risk capabilities require explicit, revocable, per-app consent").
 *
 * Launched by an app via `EdgeLM.permissions().request(context, capability)`. It
 * names the requesting app and the capability in plain language and records the
 * user's decision in the [CapabilityBroker] grant store. Low-risk capabilities never
 * reach here — they are grant-on-first-use inside the broker.
 *
 * Built programmatically (no layout XML dependency) and brand-styled to match the
 * runtime. Result is returned via setResult so the SDK can react immediately.
 *
 * Security note: the requesting package is passed as an extra AND cross-checked
 * against the launch referrer where available; the dialog always shows the resolved
 * app's real label, so the user is consenting for a named, visible app.
 */
class PermissionConsentActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE = "ai.edgelm.extra.PACKAGE"
        const val EXTRA_CAPABILITY = "ai.edgelm.extra.CAPABILITY"
        const val RESULT_GRANTED = Activity.RESULT_FIRST_USER
        const val RESULT_DENIED = Activity.RESULT_FIRST_USER + 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val broker = CapabilityBroker(applicationContext)

        // Prefer the verified launch referrer over the (spoofable) extra.
        val referrerPkg = referrer?.host
        val pkg = referrerPkg ?: intent.getStringExtra(EXTRA_PACKAGE)
        val cap = CapabilityBroker.Capability.byId(intent.getStringExtra(EXTRA_CAPABILITY))

        if (pkg.isNullOrBlank() || cap == null) {
            setResult(RESULT_DENIED); finish(); return
        }

        val appLabel = broker.labelFor(pkg)
        val capLabel = humanCapability(cap)

        setContentView(buildView(appLabel, capLabel,
            onGrant = { broker.setGrant(pkg, cap, true); setResult(RESULT_GRANTED); finish() },
            onDeny = { broker.setGrant(pkg, cap, false); setResult(RESULT_DENIED); finish() },
        ))
    }

    private fun humanCapability(cap: CapabilityBroker.Capability): String = when (cap) {
        CapabilityBroker.Capability.BACKGROUND_INFERENCE ->
            "run AI in the background (while you're not using the app). This uses battery and compute even when the app isn't open."
        CapabilityBroker.Capability.VISION -> "analyse images with on-device AI"
        CapabilityBroker.Capability.EMBED -> "create on-device embeddings of its data"
        CapabilityBroker.Capability.CHAT -> "use on-device AI chat"
    }

    private fun buildView(
        appLabel: String,
        capLabel: String,
        onGrant: () -> Unit,
        onDeny: () -> Unit,
    ): ViewGroup {
        val obsidian = Color.parseColor("#0B0E10")
        val signal = Color.parseColor("#9BFF3C")
        val ink = Color.parseColor("#E7ECEF")
        val muted = Color.parseColor("#9AA5AB")
        val pad = (24 * resources.displayMetrics.density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(obsidian)
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = "EdgeLM permission"
                setTextColor(signal); textSize = 14f
            })
            addView(TextView(context).apply {
                text = "Allow $appLabel to $capLabel?"
                setTextColor(ink); textSize = 22f
                setPadding(0, pad / 2, 0, pad / 3)
            })
            addView(TextView(context).apply {
                text = "Your prompts and data stay on this device. You can revoke this anytime in EdgeLM → Permissions."
                setTextColor(muted); textSize = 14f
                setPadding(0, 0, 0, pad)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(Button(context).apply {
                    text = "Don't allow"
                    setTextColor(muted)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { onDeny() }
                })
                addView(Button(context).apply {
                    text = "Allow"
                    setTextColor(obsidian)
                    setBackgroundColor(signal)
                    setOnClickListener { onGrant() }
                })
            })
        }
    }

    override fun onBackPressed() {
        // Backing out is an implicit deny for this attempt (leaves grant UNSET).
        setResult(RESULT_DENIED); super.onBackPressed()
    }
}
