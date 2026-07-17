package ai.edgelm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.edgelm.contract.IEdgeLMService
import ai.edgelm.contract.ITokenCallback
import ai.edgelm.runtime.BuildConfig
import ai.edgelm.runtime.ModelCatalog
import ai.edgelm.runtime.ModelStore
import ai.edgelm.runtime.R
import ai.edgelm.runtime.RuntimeActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The shared EdgeLM runtime.
 *
 * One instance, one process (:core), one model mmap'd once and shared across
 * every bound app. Two front doors sit on top of a single inference path:
 *   - Binder (AIDL) for the native SDK — carries the caller UID.
 *   - A localhost OpenAI-compatible HTTP shim (127.0.0.1:1408) for cURL / OpenAI
 *     SDKs / external tools (see docs/PHASE1-OPENAI-HTTP-SHIM.md).
 *
 * Both paths serialize on [inferenceLock] — one engine, one turn at a time. The
 * real priority scheduler (docs/PHASE1-SCHEDULER.md) replaces that later.
 */
class EdgeLMService : Service() {

    private companion object {
        const val TAG = "EdgeLMService"
        const val HTTP_PORT = 1408
        const val CHANNEL_ID = "edgelm_runtime"
        const val NOTIF_ID = 1408
        // Notification-action commands, delivered to onStartCommand.
        const val ACTION_UNLOAD = "ai.edgelm.action.UNLOAD"
        const val ACTION_LOAD = "ai.edgelm.action.LOAD"
        const val ACTION_STOP = "ai.edgelm.action.STOP"
        // Free the model after this long with zero requests (the service itself
        // stays up so apps can still reach it; the model lazily reloads on demand).
        const val IDLE_UNLOAD_MS = 5 * 60_000L
    }

    // Fires the idle auto-unload; reset on every new request.
    private val idleExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var idleTask: ScheduledFuture<*>? = null

    // ---- Ongoing "EdgeLM running" notification state --------------------------
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }
    private val requestsServed = AtomicInteger(0)   // completed generations this run
    private val activeCount = AtomicInteger(0)       // generations executing right now
    @Volatile private var activeApp: String = ""     // who the current/last request is for
    @Volatile private var lastTps: Double = 0.0      // last decode speed (tok/s)

    // The active model is chosen in the picker UI; the engine loads whatever the
    // ModelStore pointer resolves to (or "" when nothing is installed yet).
    private fun currentModelPath(): String = ModelStore.activePath(this)

    /** Friendly name of the loaded model for the notification (falls back to id). */
    private fun activeModelName(): String {
        val id = ModelStore.activeId(this) ?: return "model"
        return ModelCatalog.byId(id)?.name ?: id
    }

    // The pluggable inference backend (docs/ENGINE-ABSTRACTION.md), re-resolved per load via
    // EngineRegistry (Phase C routing). llama.cpp CPU today; LiteRT-LM GPU/NPU is wired but
    // disabled until Phase B. The service never calls NativeBridge directly — all through this seam.
    @Volatile private var engine: InferenceEngine = EngineRegistry.fallback()
    // The one live model/session, or null when nothing is resident. Opaque to the service.
    @Volatile private var session: InferenceEngine.Session? = null
    private val requestIds = AtomicLong(1)
    private val cancelled = ConcurrentHashMap<Long, AtomicBoolean>()

    // Priority-ordered admission to the single engine (Part 8). Also provides the
    // mutual exclusion the shared context needs — only one generation runs at a time.
    private val scheduler = AIScheduler()

    // Pool so multiple Binder requests can be *waiting* in the scheduler at once;
    // the scheduler (not this pool) serializes actual execution in priority order.
    private val worker = Executors.newCachedThreadPool()

    @Volatile private var http: EdgeLMHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        EngineRegistry.init(applicationContext)   // give the LiteRT engine a cache-dir Context
        createNotificationChannel()
        // Go foreground immediately so there's a visible "EdgeLM running" chip and
        // the OS treats the shared runtime as in-use (survives OEM freezers).
        startForegroundNow()
        // IMPORTANT: do NOT load the model on the main thread here — mmap + context
        // build takes seconds and would ANR. The model loads lazily on the first
        // request (runInference), which runs on a worker thread.
        if (BuildConfig.DEBUG) startHttpShim()
        else Log.i(TAG, "HTTP shim disabled in release build (Binder-only)")
        updateNotification()
    }

    /** Handles the keep-alive start and notification-action taps. A plain start
     *  (from the launcher screen) makes this a *started* foreground service so the
     *  "EdgeLM running" chip persists after the app is closed/swiped. STICKY so the
     *  OS revives the shared runtime if it's killed for memory. "Stop" tears it down. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Load/unload are heavy (native mmap/free) — never run on the main thread.
            ACTION_UNLOAD -> worker.execute { unloadModelLocked() }
            ACTION_LOAD -> worker.execute { loadModelLocked() }
            ACTION_STOP -> { stopRuntime(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    /** User asked to stop the runtime entirely — drop the notification and exit. */
    private fun stopRuntime() {
        Log.i(TAG, "stopRuntime -> user stopped the shared runtime")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Model load / unload (serialized on the engine) -----------------------

    /** Choose the inference engine for the active model + this device (Phase C routing).
     *  Resolves to llama.cpp until the LiteRT-LM backend is enabled (see EngineRegistry). */
    private fun resolveEngine(): InferenceEngine {
        val spec = ModelStore.activeId(this)?.let { ModelCatalog.byId(it) }
        return EngineRegistry.select(spec, DeviceProfile.current())
    }

    /** (Re)load the active model; returns true if a model is resident afterward. */
    private fun loadModelLocked(): Boolean = scheduler.withEngine(AIScheduler.Priority.FOREGROUND) {
        session?.let { engine.unload(it); session = null }   // unload with the OLD engine first
        val path = currentModelPath()
        engine = resolveEngine()
        session = if (path.isNotEmpty()) engine.load(path) else null
        Log.i(TAG, "loadModelLocked($path) -> loaded=${session != null} [${engine.id}]")
        attachDraftLocked()
        requestsServed.set(0); lastTps = 0.0   // new model => fresh counters
        updateNotification()
        scheduleIdleUnload()   // a manually-loaded model still auto-retires when idle
        session != null
    }

    /** If the active model declares a same-tokenizer draft (see ModelSpec.draftId) and that
     *  draft is installed, attach it for speculative decoding. No-op otherwise → the runner
     *  simply falls back to single-model decode. Call right after a successful loadModel.
     *
     *  GPU-ONLY: speculative decoding only pays off when the target's batched verification of
     *  N draft tokens is ~free, which holds on a bandwidth-bound GPU but NOT on CPU — there a
     *  batch of N costs ~N× the matmul work, so draft overhead + non-free verify makes it a net
     *  LOSS (measured ~40-55% slower on a 3B+1B pair, Mali-class SoC, CPU decode). So we only
     *  attach the draft when the chosen backend is GPU; the CPU path stays plain single-model. */
    private fun attachDraftLocked() {
        val s = session ?: return
        if (!engine.label(s).startsWith("GPU")) {
            Log.i(TAG, "draft skipped — CPU backend (speculative decoding is a net loss on CPU)"); return
        }
        val id = ModelStore.activeId(this) ?: return
        val draftId = ModelCatalog.byId(id)?.draftId ?: return
        if (draftId == id) return
        if (!ModelStore.isInstalled(this, draftId)) {
            Log.i(TAG, "draft '$draftId' not installed — single-model decode"); return
        }
        val ok = engine.attachDraft(s, ModelStore.fileFor(this, draftId).absolutePath)
        Log.i(TAG, "attachDraft('$draftId') -> $ok (speculative decoding ${if (ok) "ON" else "off"})")
    }

    /** Ensure the active model is loaded WITHOUT forcing a reload (runs the one-time
     *  CPU-vs-GPU probe on first load). Returns the engine label, or "" if no model. */
    private fun ensureLoadedLocked(): String = scheduler.withEngine(AIScheduler.Priority.FOREGROUND) {
        if (session == null) {
            val path = currentModelPath()
            engine = resolveEngine()
            session = if (path.isNotEmpty()) engine.load(path) else null
            Log.i(TAG, "ensureLoadedLocked($path) -> loaded=${session != null} [${engine.id}]")
            if (session != null) {
                attachDraftLocked()
                requestsServed.set(0); lastTps = 0.0
                updateNotification(); scheduleIdleUnload()
            }
        }
        session?.let { engine.label(it) } ?: ""
    }

    /** Unload the model to reclaim RAM; returns true if the runtime is now idle. */
    private fun unloadModelLocked(): Boolean = scheduler.withEngine(AIScheduler.Priority.FOREGROUND) {
        cancelIdleUnload()
        session?.let {
            engine.unload(it); session = null
            requestsServed.set(0); lastTps = 0.0   // fresh slate for the next session
            Log.i(TAG, "unloadModelLocked -> freed model, runtime idle")
        }
        updateNotification()
        session == null
    }

    // ---- Notification ---------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID, "EdgeLM Runtime", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the shared on-device AI runtime is active."
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(ch)
    }

    /** Human-readable line describing what the runtime is doing right now. */
    private fun statusLine(): String = when {
        activeCount.get() > 0 ->
            "Generating for ${activeApp.ifBlank { "an app" }}…"
        session != null -> buildString {
            append("Ready · ${activeModelName()}")
            val served = requestsServed.get()
            if (served > 0) append(" · $served served")
            if (lastTps > 0) append(" · ${"%.0f".format(lastTps)} tok/s")
        }
        currentModelPath().isNotEmpty() -> "Idle · ${activeModelName()} unloaded to save memory"
        else -> "Idle · no model installed yet"
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, RuntimeActivity::class.java)
                // launched from a service/notification (non-Activity context) => NEW_TASK required
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = statusLine()
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_edgelm)
            .setContentTitle("EdgeLM Runtime")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("On-device AI")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(0xFF9BFF3C.toInt())
        // Toggle action: unload to free RAM while loaded; reload if a model is
        // installed but not resident. Plus an explicit Stop so the persistent chip is
        // always dismissible. Hidden mid-generation to avoid a mid-decode tap.
        if (activeCount.get() == 0) {
            when {
                session != null ->
                    b.addAction(serviceAction("Free memory", ACTION_UNLOAD))
                currentModelPath().isNotEmpty() ->
                    b.addAction(serviceAction("Load model", ACTION_LOAD))
            }
            b.addAction(serviceAction("Stop", ACTION_STOP))
        }
        return b.build()
    }

    private fun serviceAction(title: String, action: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, EdgeLMService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, title, pi).build()
    }

    private fun startForegroundNow() {
        // If a *background* app is the one that spun us up, the OS may refuse a
        // foreground-service start (Android 12+). That's fine — we still run as a
        // normal bound service; the chip just appears next time a foreground app
        // touches the runtime. Never let this crash the shared service.
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        }.onFailure { Log.w(TAG, "startForeground refused: ${it.message}") }
    }

    private fun updateNotification() {
        runCatching { notifManager.notify(NOTIF_ID, buildNotification()) }
    }

    /** Friendly label for the app behind a Binder call (uid resolved to app name). */
    private fun appLabel(uid: Int): String {
        val pkgs = packageManager.getPackagesForUid(uid) ?: return "an app"
        val pkg = pkgs.firstOrNull() ?: return "an app"
        if (pkg == packageName) return "EdgeLM"
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg)
    }

    /** The one place inference actually happens; both front doors call this. */
    private fun runInference(
        sessionId: String,
        prompt: String,
        priority: AIScheduler.Priority,
        caller: String,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): EdgeLMHttpServer.GenStats = scheduler.withEngine(priority) {
        // Single shared context => serialize; the scheduler admits the highest
        // priority waiter next (with aging). One generation runs at a time.
        cancelIdleUnload()     // busy again — don't free the model out from under us
        // Lazily (re)load if the model was auto-unloaded while idle, or never loaded.
        if (session == null) {
            val path = currentModelPath()
            if (path.isEmpty()) return@withEngine EdgeLMHttpServer.GenStats(0, 0)
            engine = resolveEngine()
            session = engine.load(path)
            Log.i(TAG, "lazy reload on request -> loaded=${session != null} [${engine.id}]")
            if (session == null) return@withEngine EdgeLMHttpServer.GenStats(0, 0)
            attachDraftLocked()
        }
        activeApp = caller
        activeCount.incrementAndGet()
        updateNotification()   // -> "Generating for <app>…"
        try {
            val started = System.currentTimeMillis()
            val sink = object : InferenceEngine.TokenSink {
                override fun onChunk(text: String) = onToken(text)
                override fun isCancelled(): Boolean = isCancelled()
            }
            val n = engine.generate(session!!, sessionId, prompt, sink)
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > 0 && n > 0) lastTps = n * 1000.0 / elapsed
            EdgeLMHttpServer.GenStats(n, elapsed)
        } finally {
            activeCount.decrementAndGet()
            requestsServed.incrementAndGet()
            updateNotification()   // -> back to "Ready · N served"
            if (activeCount.get() == 0) scheduleIdleUnload()
        }
    }

    // ---- Idle auto-unload -----------------------------------------------------

    private fun scheduleIdleUnload() {
        idleTask?.cancel(false)
        if (session == null) return
        idleTask = idleExecutor.schedule({
            if (activeCount.get() == 0 && session != null) {
                Log.i(TAG, "idle ${IDLE_UNLOAD_MS / 60000}m -> auto-freeing model")
                unloadModelLocked()
            }
        }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelIdleUnload() {
        idleTask?.cancel(false); idleTask = null
    }

    private fun warmModels(): List<String> =
        if (session != null) listOf(activeModelName()) else emptyList()

    private fun startHttpShim() {
        http = EdgeLMHttpServer(
            port = HTTP_PORT,
            // HTTP path is stateless (OpenAI clients resend full history) -> no session,
            // and defaults to BATCH priority (no UI foreground signal).
            infer = { _, prompt, onToken, isCancelled ->
                runInference("", prompt, AIScheduler.Priority.BATCH,
                    "OpenAI HTTP client", onToken, isCancelled)
            },
            warmModels = { warmModels() },
        ).also { server ->
            runCatching { server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onSuccess { Log.i(TAG, "HTTP shim on http://127.0.0.1:$HTTP_PORT/v1") }
                .onFailure { e -> Log.e(TAG, "HTTP shim failed to start", e) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEdgeLMService.Stub() {

        override fun submit(model: String, sessionId: String, prompt: String,
                            priority: Int, callback: ITokenCallback): Long {
            val id = requestIds.getAndIncrement()
            val cancelFlag = AtomicBoolean(false)
            cancelled[id] = cancelFlag
            // Must read the caller's uid on the Binder thread, not the worker.
            val caller = appLabel(Binder.getCallingUid())

            worker.execute {
                if (session == null && currentModelPath().isEmpty()) {
                    callback.onError("no model installed — open the EdgeLM Runtime app to download one")
                    cancelled.remove(id); return@execute
                }
                try {
                    val stats = runInference(
                        sessionId,
                        prompt,
                        AIScheduler.Priority.of(priority),
                        caller,
                        onToken = { runCatching { callback.onTokens(it) } },
                        isCancelled = { cancelFlag.get() },
                    )
                    callback.onDone(stats.tokenCount, stats.elapsedMs)
                } catch (t: Throwable) {
                    runCatching { callback.onError(t.message ?: "generation failed") }
                } finally {
                    cancelled.remove(id)
                }
            }
            return id
        }

        override fun cancel(requestId: Long) {
            cancelled[requestId]?.set(true)
            session?.let { engine.cancel(it) }
        }

        override fun warmModels(): Array<String> = this@EdgeLMService.warmModels().toTypedArray()

        override fun reloadModel(): Boolean = loadModelLocked()

        override fun unloadModel(): Boolean = unloadModelLocked()

        override fun prepareEngine(): String = this@EdgeLMService.ensureLoadedLocked()
    }

    override fun onDestroy() {
        runCatching { http?.stop() }
        cancelIdleUnload(); idleExecutor.shutdownNow()
        worker.shutdownNow()
        session?.let { engine.unload(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
