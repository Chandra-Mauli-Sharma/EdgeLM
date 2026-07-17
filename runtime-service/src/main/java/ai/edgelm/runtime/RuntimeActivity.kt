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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import ai.edgelm.contract.IEdgeLMService

/**
 * Landing screen + model picker for the EdgeLM Runtime app.
 *
 * Shows the catalog (see [ModelCatalog]); the user downloads one or more models,
 * switches the active one instantly (no re-download), and can remove models to
 * reclaim space. Downloads run in [DownloadWorker] (WorkManager) so they survive the
 * app closing / process death; progress is observed via the persisted WorkInfo.
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
    private var finishedMsg: String? = null      // the message for that card

    // Download state, sourced from WorkManager's observed WorkInfo.
    private var activeDownloadId: String? = null
    private var dlPct = -1; private var dlRead = 0L; private var dlTotal = 0L

    private var simpleMode = true                // plain, recommended-first view (default)
    private var showAllSimple = false            // "Show all models" expander in simple mode
    private var onboardingLaunched = false

    private var calibratedModel: String? = null  // model id the engine probe last ran for
    private var calibrating = false              // probe in flight → Playground gated

    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout   // holds the whole models section
    private lateinit var ramLine: TextView
    private lateinit var playgroundBtn: Button
    private lateinit var engineLabelView: TextView     // "Engine: CPU / GPU · <device>"
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
            // Animate the whole splash view, not provider.iconView: on some launch
            // paths (e.g. from a notification) the library's iconView is null and
            // throws. Guard so the splash is always removed even if anything fails.
            runCatching {
                val v = provider.view
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0f),
                        ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.06f),
                        ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.06f),
                    )
                    duration = 360L
                    interpolator = AccelerateInterpolator(1.2f)
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = provider.remove()
                    })
                }.start()
            }.onFailure { provider.remove() }
        }

        super.onCreate(savedInstanceState)
        // API 35 is edge-to-edge by default; use light bar icons (dark UI) and
        // transparent bars, then pad content by the system-bar insets below.
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )
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

        // Shows which backend the one-time probe picked, e.g. "Engine: GPU · Mali-G615 MC6".
        engineLabelView = centered(label("", 12f, steel, false)).apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(engineLabelView)

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
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(24) + b.left, dp(16) + b.top, dp(24) + b.right, dp(24) + b.bottom)
            insets
        }
        simpleMode = Prefs.isSimpleMode(this)
        applyMode()
        askNotificationPermission()
        observeDownloads()
    }

    /** Reconcile the UI from WorkManager — survives process death, restores progress. */
    private fun observeDownloads() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE)
            .observe(this) { infos -> onWorkInfo(infos?.lastOrNull()) }
    }

    private fun onWorkInfo(info: WorkInfo?) {
        val id = info?.tags?.firstOrNull { it.startsWith(DownloadWorker.TAG_PREFIX) }
            ?.removePrefix(DownloadWorker.TAG_PREFIX)
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                activeDownloadId = id
                dlPct = info.progress.getInt(DownloadWorker.KEY_PCT, -1)
                dlRead = info.progress.getLong(DownloadWorker.KEY_READ, 0)
                dlTotal = info.progress.getLong(DownloadWorker.KEY_TOTAL, 0)
            }
            WorkInfo.State.SUCCEEDED -> { activeDownloadId = null; lastFinishedId = id }
            WorkInfo.State.FAILED -> {
                activeDownloadId = null; lastFinishedId = id
            }
            WorkInfo.State.CANCELLED -> { activeDownloadId = null; lastFinishedId = id }
            null -> { activeDownloadId = null }
        }
        // stash a result message for the finished card
        finishedMsg = when (info?.state) {
            WorkInfo.State.SUCCEEDED -> "Installed & active ✓"
            WorkInfo.State.CANCELLED -> "Download cancelled"
            WorkInfo.State.FAILED ->
                "Download failed: " + (info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "error")
            else -> finishedMsg
        }
        refresh()
    }

    /** Applies Simple vs Advanced chrome and rebuilds the model list. */
    private fun applyMode() {
        modeToggle.text = if (simpleMode) "Advanced ›" else "‹ Simple"
        modelsHeading.text = if (simpleMode) "Choose your AI" else "Models"
        if (calibrating) { playgroundBtn.text = "Preparing the engine…"; playgroundBtn.isEnabled = false }
        else             { playgroundBtn.text = playgroundNormalText(); playgroundBtn.isEnabled = true }
        ramLine.visibility = if (!simpleMode && deviceRamMb > 0) View.VISIBLE else View.GONE
        if (deviceRamMb > 0) ramLine.text = "This device: ${gb(deviceRamMb.toLong() * 1024 * 1024)} GB RAM"
        renderModels()
        refresh()
    }

    /** Rebuilds the models section for the current mode. */
    private fun renderModels() {
        listContainer.removeAllViews()
        rows.clear()
        // Hide LiteRT-only models on devices where the GPU backend was probed and failed.
        val visible = ModelCatalog.visibleModels(this)
        if (simpleMode) {
            val recommended = ModelCatalog.recommendedFor(deviceRamMb)
            val ctx = this
            val shown = buildList {
                add(recommended)
                if (showAllSimple) {
                    visible.forEach { if (it.id != recommended.id) add(it) }
                } else {
                    // keep any already-installed models visible so they can switch
                    visible.forEach {
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
            visible.forEach { listContainer.addView(modelCard(it, simple = false, recommended = false)) }
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
            setOnClickListener { onSecondaryClick(spec) }
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

    // ---- Engine calibration (one-time CPU-vs-GPU probe, gates Playground) ------

    private fun playgroundNormalText() =
        if (simpleMode) "▶  Try it — chat with the AI" else "▶  Open Playground — test the runtime"

    /** On first use of a model, warm the engine (which runs the CPU-vs-GPU probe) with
     *  the Playground button disabled, then show which backend was chosen. Runs once per
     *  model; the native side caches the choice so later launches only pay the load time. */
    private fun maybeCalibrate() {
        val svc = service ?: return
        val activeId = ModelStore.activeId(this) ?: return
        if (calibrating || activeDownloadId != null || activeId == calibratedModel) return

        calibrating = true
        playgroundBtn.text = "Preparing the engine…"
        playgroundBtn.isEnabled = false
        engineLabelView.text = "Optimizing for your device (one-time)…"
        engineLabelView.visibility = View.VISIBLE

        Thread {
            val label = runCatching { svc.prepareEngine() }.getOrDefault("")
            runOnUiThread {
                calibrating = false
                calibratedModel = activeId
                playgroundBtn.text = playgroundNormalText()
                playgroundBtn.isEnabled = true
                if (label.isNotEmpty()) {
                    engineLabelView.text = "Engine: $label"
                    engineLabelView.visibility = View.VISIBLE
                } else {
                    // Engine failed to load (e.g. LiteRT GPU init failed → its verdict is now
                    // cached as unusable). Surface it and refresh the picker so the unrunnable
                    // model drops out (ModelCatalog.visibleModels), guiding the user to another.
                    engineLabelView.text = "This model couldn't start on your device — pick another."
                    engineLabelView.visibility = View.VISIBLE
                    renderModels()
                }
            }
        }.start()
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

        // Warm + probe the engine on first use of the active model (gates Playground).
        maybeCalibrate()

        val downloadingId = activeDownloadId
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
                // secondary button becomes an in-app Cancel while downloading
                row.secondaryBtn.visibility = View.VISIBLE
                row.secondaryBtn.text = "Cancel"
                row.secondaryBtn.setTextColor(Color.parseColor("#FF6B7A"))
                // progress driven by the observed WorkInfo
                row.progress.visibility = View.VISIBLE
                row.progressText.visibility = View.VISIBLE
                if (dlPct >= 0) {
                    row.progress.isIndeterminate = false; row.progress.progress = dlPct
                    row.progressText.text = "Downloading… $dlPct%   (${fmtMb((dlRead / 1_048_576).toInt())} / ${fmtMb((dlTotal / 1_048_576).toInt())})"
                } else {
                    row.progress.isIndeterminate = true
                    row.progressText.text = "Starting download…"
                }
                continue
            }

            row.progress.visibility = View.GONE
            row.secondaryBtn.setTextColor(steel)   // reset from the red "Cancel" state
            // Keep a one-shot result line on the just-finished card; clear others.
            if (spec.id == lastFinishedId && finishedMsg != null) {
                row.progressText.visibility = View.VISIBLE
                row.progressText.text = finishedMsg
            } else {
                row.progressText.visibility = View.GONE
            }

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

    /** Run a blocking Binder call off the UI thread, then refresh. reloadModel/
     *  unloadModel do heavy native work; calling them on the main thread ANRs. */
    private fun runtimeCall(work: (IEdgeLMService) -> Unit) {
        val svc = service ?: return
        Thread { runCatching { work(svc) }; runOnUiThread { refresh() } }.start()
    }

    private fun onActionClick(spec: ModelSpec) {
        if (activeDownloadId != null) return   // one download at a time
        if (ModelStore.isInstalled(this, spec.id)) {
            if (ModelStore.activeId(this) == spec.id) return // already active
            ModelStore.setActive(this, spec.id)
            lastFinishedId = null
            runtimeCall { it.reloadModel() }
            refresh()
        } else {
            startDownload(spec)
        }
    }

    /** The card's secondary button: Cancel while this model is downloading, else Remove. */
    private fun onSecondaryClick(spec: ModelSpec) {
        if (activeDownloadId == spec.id) cancelDownload() else onRemoveClick(spec)
    }

    private fun cancelDownload() {
        WorkManager.getInstance(this).cancelUniqueWork(DownloadWorker.UNIQUE)
        // UI updates via the observed WorkInfo (state -> CANCELLED)
    }

    private fun onRemoveClick(spec: ModelSpec) {
        if (activeDownloadId != null) return
        val wasActive = ModelStore.activeId(this) == spec.id
        ModelStore.remove(this, spec.id)
        if (wasActive) runtimeCall { it.unloadModel() }  // nothing active now
        if (lastFinishedId == spec.id) { lastFinishedId = null; finishedMsg = null }
        refresh()
    }

    /** Enqueue a durable WorkManager download; progress arrives via the observed WorkInfo. */
    private fun startDownload(spec: ModelSpec) {
        lastFinishedId = null; finishedMsg = null
        activeDownloadId = spec.id; dlPct = -1
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to spec.id))
            .addTag(DownloadWorker.TAG_PREFIX + spec.id)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(DownloadWorker.UNIQUE, ExistingWorkPolicy.KEEP, req)
        refresh()
    }

    // ---- Lifecycle ------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // First run: show the welcome once.
        if (!Prefs.isOnboarded(this) && !onboardingLaunched) {
            onboardingLaunched = true
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        // Download progress is observed via WorkManager (observeDownloads); it's
        // lifecycle-aware and reconciles across process death, so nothing to wire here.
        val i = Intent().setComponent(ComponentName(packageName, "ai.edgelm.service.EdgeLMService"))
        runCatching { startForegroundService(i) }
        runCatching { bindService(i, conn, Context.BIND_AUTO_CREATE) }
        refresh()
    }
    override fun onStop() {
        super.onStop()
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
