package ai.edgelm.runtime

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.animation.ObjectAnimator
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ai.edgelm.contract.IEdgeLMService

/**
 * Landing screen + model picker for the EdgeLM Runtime app.
 *
 * Shows the catalog (see [ModelCatalog]); the user downloads one or more models,
 * switches the active one instantly (no re-download), and can remove models to
 * reclaim space. Downloads run in [ModelDownloadService] (a foreground service) so
 * they survive leaving this screen; progress is observed via the [Downloads] bus.
 */
class RuntimeActivity : ComponentActivity() {

    // Brand palette.
    private val bg = Color.parseColor("#0B0E10")
    private val carbon = Color.parseColor("#14181B")
    private val mist = Color.parseColor("#EEF2F0")
    private val fog = Color.parseColor("#B4C0C5")
    private val green = Color.parseColor("#9BFF3C")
    private val steel = Color.parseColor("#84939B")
    private val amber = Color.parseColor("#FFC24B")

    private var service: IEdgeLMService? = null
    private var lastFinishedId: String? = null   // show a one-shot result line on this card
    @Volatile private var firstFrameReady = false  // releases the splash once UI is built

    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private var deviceRamMb: Int = 0

    /** Per-model card widgets we mutate on state changes. */
    private class Row(
        val actionBtn: Button,
        val secondaryBtn: Button,
        val progress: ProgressBar,
        val progressText: TextView,
    )
    private val rows = LinkedHashMap<String, Row>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IEdgeLMService.Stub.asInterface(binder); refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Branded splash: hold briefly until the runtime binds, then animate the
        // ghost out with a slide-up + fade for a polished handoff into the app.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !firstFrameReady }
        splash.setOnExitAnimationListener { provider ->
            val icon = provider.iconView
            val slide = ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -icon.height.toFloat() * 0.5f)
            val fade = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f)
            val zoom = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 0.7f)
            val zoomY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 0.7f)
            listOf(slide, fade, zoom, zoomY).forEach {
                it.interpolator = AnticipateInterpolator(); it.duration = 320L
            }
            slide.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = provider.remove()
            })
            slide.start(); fade.start(); zoom.start(); zoomY.start()
        }

        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        deviceRamMb = runCatching {
            val mi = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            (mi.totalMem / (1024 * 1024)).toInt()
        }.getOrDefault(0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(24), dp(52), dp(24), dp(48))
        }

        // Header
        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        root.addView(centered(label("EdgeLM Runtime", 26f, mist, true)).apply { setPadding(0, dp(16), 0, 0) })
        root.addView(centered(label("The Android AI Runtime", 14.5f, green, false)).apply { setPadding(0, dp(3), 0, 0) })

        statusView = label("Checking runtime…", 15f, mist, true).apply {
            setPadding(0, dp(22), 0, dp(2)); gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(statusView)
        root.addView(centered(label(
            if (deviceRamMb > 0) "This device: ${gb(deviceRamMb.toLong() * 1024 * 1024)} GB RAM" else "",
            12f, steel, false)))

        // Playground launcher — quick way to verify the runtime + model answer.
        root.addView(Button(this).apply {
            text = "▶  Open Playground — test the runtime"
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD); textSize = 14.5f
            setTextColor(green); background = ghostGreenBg(); stateListAnimator = null
            setPadding(dp(18), dp(13), dp(18), dp(13))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
            setOnClickListener {
                startActivity(Intent(this@RuntimeActivity, PlaygroundActivity::class.java))
            }
        })

        root.addView(label("Choose a model", 17f, mist, true).apply { setPadding(dp(2), dp(28), 0, dp(2)) })
        root.addView(label(
            "Download one or more models. Switch the active model any time — installed " +
            "models switch instantly without re-downloading. Apps share whichever is active.",
            13.5f, fog, false).apply { setPadding(dp(2), 0, 0, dp(8)) })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        ModelCatalog.models.forEach { listContainer.addView(modelCard(it)) }

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")
        root.addView(label("v$ver  ·  ai.edgelm.runtime  ·  private, on-device", 11.5f, steel, false)
            .apply { setPadding(dp(2), dp(24), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        // Let the splash dismiss after the first frame is laid out.
        window.decorView.post { firstFrameReady = true }
        askNotificationPermission()
    }

    // ---- One model card -------------------------------------------------------

    private fun modelCard(spec: ModelSpec): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }

        // Title row: name + params pill
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(label(spec.name, 16.5f, mist, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        titleRow.addView(pill("${spec.params} · ${spec.quant}"))
        card.addView(titleRow)

        // Meta line
        card.addView(label(
            "${fmtMb(spec.sizeMb)}  ·  ${spec.ctx} context  ·  ${spec.license}  ·  needs ~${gbFromMb(spec.minRamMb)} GB RAM",
            12f, steel, false).apply { setPadding(0, dp(7), 0, 0) })

        // RAM warning if the device likely can't run it
        if (deviceRamMb in 1 until spec.minRamMb) {
            card.addView(label("⚠ May exceed this device's RAM — could fail to load.", 12f, amber, false)
                .apply { setPadding(0, dp(5), 0, 0) })
        }

        // Blurb
        card.addView(label(spec.blurb, 13f, fog, false).apply { setPadding(0, dp(8), 0, dp(2)) })

        // Recommended use case
        card.addView(label("Best for: ${spec.useCase}", 12.5f, Color.parseColor("#9DBE7A"), false)
            .apply { setPadding(0, dp(6), 0, dp(2)); setTypeface(typeface, Typeface.ITALIC) })

        // Buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val actionBtn = Button(this).apply {
            isAllCaps = false; typeface = Typeface.create(typeface, Typeface.BOLD); textSize = 14f
            stateListAnimator = null
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onActionClick(spec) }
        }
        val secondaryBtn = Button(this).apply {
            text = "Remove"; isAllCaps = false; textSize = 13.5f
            setTextColor(steel); background = ghostBg(); stateListAnimator = null
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) }
            setOnClickListener { onRemoveClick(spec) }
        }
        btnRow.addView(actionBtn); btnRow.addView(secondaryBtn)
        card.addView(btnRow)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressDrawable = greenProgress()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(12) }
        }
        val progressText = label("", 12f, fog, false).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        card.addView(progress); card.addView(progressText)

        rows[spec.id] = Row(actionBtn, secondaryBtn, progress, progressText)
        return card
    }

    // ---- State refresh --------------------------------------------------------

    private fun refresh() {
        val svc = service
        val activeId = ModelStore.activeId(this)
        val loadedName = runCatching { svc?.warmModels()?.firstOrNull() }.getOrNull()
        statusView.text = if (svc != null) "● Runtime active" else "○ Runtime not connected"
        statusView.setTextColor(if (svc != null) green else Color.parseColor("#FF6B7A"))

        val downloadingId = Downloads.activeId
        for (spec in ModelCatalog.models) {
            val row = rows[spec.id] ?: continue
            val installed = ModelStore.isInstalled(this, spec.id)
            val isActive = spec.id == activeId
            val isDownloadingThis = downloadingId == spec.id
            val busyElsewhere = downloadingId != null && !isDownloadingThis

            if (isDownloadingThis) {
                row.actionBtn.text = "Downloading…"
                row.actionBtn.isEnabled = false
                row.actionBtn.setTextColor(Color.parseColor("#0B0E10"))
                row.actionBtn.background = pillBg()
                row.secondaryBtn.visibility = View.GONE
                continue // progress views driven by onDownloadUpdate()
            }

            row.progress.visibility = View.GONE
            // Keep a one-shot result line on the just-finished card; clear others.
            if (spec.id != lastFinishedId) row.progressText.visibility = View.GONE

            when {
                isActive -> {
                    val warm = loadedName != null
                    row.actionBtn.text = if (warm) "Active ✓ (warm)" else "Active ✓"
                    row.actionBtn.background = pillBg()
                    row.actionBtn.setTextColor(Color.parseColor("#0B0E10"))
                    row.actionBtn.isEnabled = false
                    row.secondaryBtn.visibility = View.VISIBLE
                    row.secondaryBtn.text = "Remove"
                }
                installed -> {
                    row.actionBtn.text = "Use this model"
                    row.actionBtn.background = ghostGreenBg()
                    row.actionBtn.setTextColor(green)
                    row.actionBtn.isEnabled = !busyElsewhere
                    row.secondaryBtn.visibility = View.VISIBLE
                    row.secondaryBtn.text = "Remove"
                }
                else -> {
                    row.actionBtn.text = "Download  ·  ${fmtMb(spec.sizeMb)}"
                    row.actionBtn.background = pillBg()
                    row.actionBtn.setTextColor(Color.parseColor("#0B0E10"))
                    row.actionBtn.isEnabled = !busyElsewhere
                    row.secondaryBtn.visibility = View.GONE
                }
            }
        }
    }

    // ---- Actions --------------------------------------------------------------

    private fun onActionClick(spec: ModelSpec) {
        if (Downloads.activeId != null) return   // one download at a time
        if (ModelStore.isInstalled(this, spec.id)) {
            if (ModelStore.activeId(this) == spec.id) return // already active
            ModelStore.setActive(this, spec.id)
            lastFinishedId = null
            runCatching { service?.reloadModel() }
            refresh()
        } else {
            startDownload(spec)
        }
    }

    private fun onRemoveClick(spec: ModelSpec) {
        if (Downloads.activeId != null) return
        val wasActive = ModelStore.activeId(this) == spec.id
        ModelStore.remove(this, spec.id)
        if (wasActive) runCatching { service?.unloadModel() }  // nothing active now
        if (lastFinishedId == spec.id) lastFinishedId = null
        refresh()
    }

    /** Kick off the background download service; progress arrives via [Downloads]. */
    private fun startDownload(spec: ModelSpec) {
        lastFinishedId = null
        val row = rows[spec.id]
        row?.progress?.apply { visibility = View.VISIBLE; isIndeterminate = true }
        row?.progressText?.apply { visibility = View.VISIBLE; text = "Starting download…" }
        runCatching {
            startForegroundService(
                Intent(this, ModelDownloadService::class.java)
                    .putExtra(ModelDownloadService.EXTRA_ID, spec.id)
            )
        }
        refresh()
    }

    /** Called (on main thread) by the [Downloads] bus as a download progresses/ends. */
    private fun onDownloadUpdate(id: String) {
        val row = rows[id] ?: return
        if (Downloads.activeId == id) {
            row.progress.visibility = View.VISIBLE
            row.progressText.visibility = View.VISIBLE
            val pct = Downloads.pct
            if (pct >= 0) {
                row.progress.isIndeterminate = false; row.progress.progress = pct
                row.progressText.text =
                    "Downloading… $pct%   (${fmtMb((Downloads.read / 1_048_576).toInt())} / ${fmtMb((Downloads.total / 1_048_576).toInt())})"
            } else {
                row.progress.isIndeterminate = true
                row.progressText.text = "Starting download…"
            }
            refresh()
        } else {
            // Finished (success / cancel / error).
            lastFinishedId = id
            val err = Downloads.lastError
            row.progress.visibility = View.GONE
            row.progressText.visibility = View.VISIBLE
            row.progressText.text = when {
                err == null -> "Installed & active ✓"
                err == "cancelled" -> "Download cancelled"
                else -> "Download failed: $err"
            }
            refresh()
        }
    }

    // ---- Lifecycle ------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // Observe download progress while visible; reconcile anything already running.
        Downloads.listener = { id -> runOnUiThread { onDownloadUpdate(id) } }
        val i = Intent().setComponent(ComponentName(packageName, "ai.edgelm.service.EdgeLMService"))
        runCatching { startForegroundService(i) }
        runCatching { bindService(i, conn, Context.BIND_AUTO_CREATE) }
        Downloads.activeId?.let { onDownloadUpdate(it) }
        refresh()
    }
    override fun onStop() {
        super.onStop()
        Downloads.listener = null
        runCatching { unbindService(conn) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1408)
        }
    }

    // ---- Small view helpers ---------------------------------------------------

    private fun label(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun centered(v: TextView) = v.apply {
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun pill(text: String) = TextView(this).apply {
        this.text = text; textSize = 11.5f; setTextColor(green)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat(); setColor(Color.parseColor("#182013"))
            setStroke(dp(1), Color.parseColor("#2E3941"))
        }
        typeface = Typeface.MONOSPACE
    }

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat(); setColor(carbon); setStroke(dp(1), Color.parseColor("#242D33"))
    }

    // Filled green pill (primary).
    private fun pillBg(): StateListDrawable {
        fun d(c: String) = GradientDrawable().apply { cornerRadius = dp(13).toFloat(); setColor(Color.parseColor(c)) }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), d("#5A7A2E"))
            addState(intArrayOf(android.R.attr.state_pressed), d("#7CEB1E"))
            addState(intArrayOf(), d("#9BFF3C"))
        }
    }
    // Green outline (secondary, active-switch).
    private fun ghostGreenBg(): StateListDrawable {
        fun d(fill: Int) = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat(); setColor(fill); setStroke(dp(1), green)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), d(Color.parseColor("#182013")))
            addState(intArrayOf(), d(Color.TRANSPARENT))
        }
    }
    // Neutral outline (Remove).
    private fun ghostBg(): StateListDrawable {
        fun d(fill: Int) = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat(); setColor(fill); setStroke(dp(1), Color.parseColor("#2E3941"))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), d(Color.parseColor("#1A2024")))
            addState(intArrayOf(), d(Color.TRANSPARENT))
        }
    }
    private fun greenProgress(): android.graphics.drawable.Drawable {
        val r = dp(6).toFloat()
        val track = GradientDrawable().apply { cornerRadius = r; setColor(Color.parseColor("#1E262B")) }
        val fill = GradientDrawable().apply { cornerRadius = r; setColor(green) }
        return android.graphics.drawable.LayerDrawable(arrayOf(
            track,
            android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
    }

    private fun fmtMb(mb: Int) = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
    private fun gb(bytes: Long) = "%.0f".format(bytes / 1.0e9).let { it }
    private fun gbFromMb(mb: Int) = "%.1f".format(mb / 1024.0)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
