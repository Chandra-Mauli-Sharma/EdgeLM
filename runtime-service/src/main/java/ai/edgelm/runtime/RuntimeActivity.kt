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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.SystemClock
import android.view.animation.AccelerateInterpolator
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

    private var simpleMode = true                // plain, recommended-first view (default)
    private var showAllSimple = false            // "Show all models" expander in simple mode
    private var onboardingLaunched = false

    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout   // holds the whole models section
    private lateinit var ramLine: TextView
    private lateinit var playgroundBtn: Button
    private lateinit var modeToggle: TextView
    private lateinit var modelsHeading: TextView
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
        // Branded splash: keep the ghost on screen for a clear, visible beat, then
        // animate it out (rise + gentle zoom + fade) so the handoff reads smoothly
        // instead of flashing past.
        val splashStart = SystemClock.uptimeMillis()
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() - splashStart < 850L }
        splash.setOnExitAnimationListener { provider ->
            val icon = provider.iconView
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -icon.height * 0.35f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 0.72f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 0.72f),
                )
                duration = 480L
                interpolator = AccelerateInterpolator(1.3f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = provider.remove()
                })
            }
            set.start()
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
        root.addView(centered(label("EdgeLM", 26f, mist, true)).apply { setPadding(0, dp(16), 0, 0) })
        root.addView(centered(label("AI that runs on your phone", 14.5f, green, false)).apply { setPadding(0, dp(3), 0, 0) })

        statusView = label("Checking…", 15f, mist, true).apply {
            setPadding(0, dp(20), 0, dp(2)); gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(statusView)
        ramLine = centered(label("", 12f, steel, false))
        root.addView(ramLine)

        // Playground launcher — quick way to verify the runtime + model answer.
        playgroundBtn = Button(this).apply {
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD); textSize = 14.5f
            setTextColor(green); background = ghostGreenBg(); stateListAnimator = null
            setPadding(dp(18), dp(13), dp(18), dp(13))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
            setOnClickListener { startActivity(Intent(this@RuntimeActivity, PlaygroundActivity::class.java)) }
        }
        root.addView(playgroundBtn)

        // Section heading row: title + Simple/Advanced toggle.
        val headingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(26) }
        }
        modelsHeading = label("Models", 17f, mist, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        headingRow.addView(modelsHeading)
        modeToggle = label("", 13f, green, false).apply {
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener {
                simpleMode = !simpleMode
                Prefs.setSimpleMode(this@RuntimeActivity, simpleMode)
                showAllSimple = false
                applyMode()
            }
        }
        headingRow.addView(modeToggle)
        root.addView(headingRow)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")
        root.addView(label("v$ver  ·  private, on-device", 11.5f, steel, false)
            .apply { setPadding(dp(2), dp(24), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        simpleMode = Prefs.isSimpleMode(this)
        applyMode()
        askNotificationPermission()
    }

    /** Applies Simple vs Advanced chrome and rebuilds the model list. */
    private fun applyMode() {
        modeToggle.text = if (simpleMode) "Advanced ›" else "‹ Simple"
        modelsHeading.text = if (simpleMode) "Choose your AI" else "Models"
        playgroundBtn.text = if (simpleMode) "▶  Try it — chat with the AI"
                             else "▶  Open Playground — test the runtime"
        ramLine.visibility = if (!simpleMode && deviceRamMb > 0) View.VISIBLE else View.GONE
        if (deviceRamMb > 0) ramLine.text = "This device: ${gb(deviceRamMb.toLong() * 1024 * 1024)} GB RAM"
        renderModels()
        refresh()
    }

    /** Rebuilds the models section for the current mode. */
    private fun renderModels() {
        listContainer.removeAllViews()
        rows.clear()
        if (simpleMode) {
            val recommended = ModelCatalog.recommendedFor(deviceRamMb)
            val ctx = this
            val shown = buildList {
                add(recommended)
                if (showAllSimple) {
                    ModelCatalog.models.forEach { if (it.id != recommended.id) add(it) }
                } else {
                    // keep any already-installed models visible so they can switch
                    ModelCatalog.models.forEach {
                        if (it.id != recommended.id && ModelStore.isInstalled(ctx, it.id)) add(it)
                    }
                }
            }
            shown.forEach { listContainer.addView(modelCard(it, simple = true, recommended = it.id == recommended.id)) }
            if (!showAllSimple) {
                listContainer.addView(label("Show more options  ▾", 13.5f, green, false).apply {
                    setPadding(dp(4), dp(16), dp(4), dp(8)); gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    setOnClickListener { showAllSimple = true; renderModels(); refresh() }
                })
            }
        } else {
            ModelCatalog.models.forEach { listContainer.addView(modelCard(it, simple = false, recommended = false)) }
        }
    }

    // ---- One model card -------------------------------------------------------

    private fun modelCard(spec: ModelSpec, simple: Boolean, recommended: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (recommended) recommendedCardBg() else cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }

        if (recommended) {
            card.addView(label("★  RECOMMENDED FOR YOUR PHONE", 11f, green, true)
                .apply { setPadding(0, 0, 0, dp(8)); letterSpacing = 0.04f })
        }

        // Title row: friendly name in simple; real name + params pill in advanced.
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(label(if (simple) spec.simpleName else spec.name, 16.5f, mist, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        if (!simple) titleRow.addView(pill("${spec.params} · ${spec.quant}"))
        card.addView(titleRow)

        if (simple) {
            // Plain language only: friendly speed word + download size, then a
            // jargon-free one-liner. No model names, params, context, or RAM figures.
            card.addView(label("${ModelCatalog.hintFor(spec)}  ·  ${fmtMb(spec.sizeMb)} to download",
                12.5f, steel, false).apply { setPadding(0, dp(7), 0, 0) })
            if (deviceRamMb in 1 until spec.minRamMb) {
                card.addView(label("⚠ May be too big for this phone.", 12f, amber, false)
                    .apply { setPadding(0, dp(5), 0, 0) })
            }
            card.addView(label(spec.simpleTagline, 13f, fog, false).apply { setPadding(0, dp(8), 0, dp(2)) })
        } else {
            card.addView(label(
                "${fmtMb(spec.sizeMb)}  ·  ${spec.ctx} context  ·  ${spec.license}  ·  needs ~${gbFromMb(spec.minRamMb)} GB RAM",
                12f, steel, false).apply { setPadding(0, dp(7), 0, 0) })
            if (deviceRamMb in 1 until spec.minRamMb) {
                card.addView(label("⚠ May exceed this device's RAM — could fail to load.", 12f, amber, false)
                    .apply { setPadding(0, dp(5), 0, 0) })
            }
            card.addView(label(spec.blurb, 13f, fog, false).apply { setPadding(0, dp(8), 0, dp(2)) })
            card.addView(label("Best for: ${spec.useCase}", 12.5f, Color.parseColor("#9DBE7A"), false)
                .apply { setPadding(0, dp(6), 0, dp(2)); setTypeface(typeface, Typeface.ITALIC) })
        }

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
        val anyInstalled = ModelStore.installedIds(this).isNotEmpty()
        statusView.text = when {
            svc == null -> "Connecting…"
            !anyInstalled -> if (simpleMode) "Pick an AI below to get started" else "○ No model installed"
            else -> if (simpleMode) "✓ Ready to use" else "● Runtime active"
        }
        statusView.setTextColor(when {
            svc == null -> steel
            !anyInstalled -> amber
            else -> green
        })

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
                    row.actionBtn.text = when {
                        simpleMode -> "In use ✓"
                        warm -> "Active ✓ (warm)"
                        else -> "Active ✓"
                    }
                    row.actionBtn.background = pillBg()
                    row.actionBtn.setTextColor(Color.parseColor("#0B0E10"))
                    row.actionBtn.isEnabled = false
                    row.secondaryBtn.visibility = View.VISIBLE
                    row.secondaryBtn.text = "Remove"
                }
                installed -> {
                    row.actionBtn.text = if (simpleMode) "Use this one" else "Use this model"
                    row.actionBtn.background = ghostGreenBg()
                    row.actionBtn.setTextColor(green)
                    row.actionBtn.isEnabled = !busyElsewhere
                    row.secondaryBtn.visibility = View.VISIBLE
                    row.secondaryBtn.text = "Remove"
                }
                else -> {
                    row.actionBtn.text = if (simpleMode) "Download" else "Download  ·  ${fmtMb(spec.sizeMb)}"
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
        // First run: show the welcome once.
        if (!Prefs.isOnboarded(this) && !onboardingLaunched) {
            onboardingLaunched = true
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
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
    // Highlighted card for the recommended model — subtle green tint + border.
    private fun recommendedCardBg() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#141B10")); setStroke(dp(1), Color.parseColor("#3A5A22"))
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
