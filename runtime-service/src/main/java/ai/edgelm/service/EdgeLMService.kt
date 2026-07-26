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
import ai.edgelm.runtime.VectorStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        // ADPF target per-token work duration. Set deliberately BELOW the observed pace (~59 ms/token
        // at 17 tok/s) so the hint asks the governor to boost clocks toward it. ~25 ms ≈ a 40 tok/s
        // aspiration; the system won't exceed hardware limits, it just stops under-clocking.
        const val TARGET_TOKEN_NANOS = 25_000_000L
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

    // Battery/thermal governor consulted by the scheduler to defer background work
    // under heat or low battery (Part 8 hard clamp).
    private val deviceGovernor by lazy { DeviceGovernor(applicationContext) }

    // Weighted-fair, governed admission to the single engine (Part 8). Provides the
    // mutual exclusion the shared context needs (one generation at a time), picks the
    // next waiter by priority + aging + per-app fair share, and lets the governor
    // defer/deny background jobs when the device is hot or low on battery.
    private val scheduler by lazy { AIScheduler(governor = { deviceGovernor.snapshot() }) }

    // Fine-grained capability gate on top of the coarse USE_RUNTIME bind permission
    // (Part 7). Maps the Binder UID -> package -> granted capabilities, grant-on-
    // first-use for low-risk, explicit consent for high-risk, per-app rate quota.
    private val broker by lazy { CapabilityBroker(applicationContext) }

    // OPT-IN continuous-batching engine (increment 2 integration). When non-null, requests
    // route to it (concurrent decode, one driver thread) instead of the serialized
    // scheduler path. Enabled via POST /v1/edge/batched-mode; needs a -DEDGELM_BATCHED
    // native build. Null = the default, proven single-context path.
    @Volatile private var batchedSession: BatchedRuntimeSession? = null

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

    /** Route a request to the batched engine if enabled, else the default serialized path.
     *  Both front doors (Binder + HTTP) go through here. */
    private fun dispatchInference(
        sessionId: String,
        prompt: String,
        priority: AIScheduler.Priority,
        caller: String,
        uid: Int,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean,
        system: String = "",
        grammar: String = "",
    ): EdgeLMHttpServer.GenStats {
        val b = batchedSession
            ?: return runInference(sessionId, prompt, priority, caller, onToken, isCancelled, system, grammar)
        // NOTE: the batched engine uses its own COW system template; per-request system
        // prompts aren't applied there yet (documented limitation).
        // Batched path: concurrent decode, no scheduler lock. Mirror the notification/idle
        // bookkeeping runInference does so the UI + auto-unload still behave.
        cancelIdleUnload()
        activeApp = caller; activeCount.incrementAndGet(); updateNotification()
        return try {
            b.generate(uid, sessionId, prompt, priority, onToken, isCancelled).also {
                if (it.elapsedMs > 0 && it.tokenCount > 0) lastTps = it.tokenCount * 1000.0 / it.elapsedMs
            }
        } finally {
            activeCount.decrementAndGet(); requestsServed.incrementAndGet(); updateNotification()
        }
    }

    /** Enable/disable the batched engine. Creation loads the model (backend probe), so it's
     *  done off-thread; requests transparently use the default path until it's ready. */
    private fun setBatchedMode(on: Boolean): String {
        if (on) {
            if (batchedSession != null) return org.json.JSONObject().put("batched", true).put("note", "already on").toString()
            val path = currentModelPath()
            if (path.isEmpty()) return org.json.JSONObject().put("error", "no model installed").toString()
            worker.execute {
                val s = BatchedRuntimeSession.create(path)
                if (s != null) { batchedSession = s; Log.i(TAG, "batched mode ON") }
                else Log.e(TAG, "batched mode failed — native lib built without -DEDGELM_BATCHED?")
            }
            return org.json.JSONObject().put("batched", "starting")
                .put("note", "loading model; needs a -DEDGELM_BATCHED build. watch logcat 'edgelm-batched-svc'").toString()
        } else {
            batchedSession?.let { worker.execute { it.shutdown() }; batchedSession = null }
            return org.json.JSONObject().put("batched", false).toString()
        }
    }

    /** The one place inference actually happens; both front doors call this. */
    private fun runInference(
        sessionId: String,
        prompt: String,
        priority: AIScheduler.Priority,
        caller: String,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean,
        system: String = "",
        grammar: String = "",
    ): EdgeLMHttpServer.GenStats = scheduler.withEngine(priority, appId = caller) { _ ->
        // Single shared context => serialize; the scheduler admits the highest effective
        // score next (priority + aging + per-app fair share). One generation at a time.
        // The Preemption signal (unused arg) is available for a future engine that can
        // yield a background decode to a foreground request (continuous batching).
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
        // Raise this worker's scheduler priority for the duration of the decode. The native
        // threadpool already runs its compute threads at high priority, but bumping the JNI-calling
        // thread too keeps the DVFS governor seeing a busy high-priority thread (helps hold clocks)
        // and reduces the chance of preemption stalling llama_decode. Restored in finally.
        val prevPrio = android.os.Process.getThreadPriority(android.os.Process.myTid())
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)  // -19
        // ADPF hint session (API 31+): tell the scheduler this thread has a tight per-token deadline
        // so it raises CPU **and memory-controller** clocks to meet it — the direct lever on
        // bandwidth-bound decode, and smarter than the blunt sustained-mode cap. We report the
        // actual time between streamed tokens; a target set below the current pace asks for a boost.
        // Best-effort: null on unsupported devices. The calling thread is ggml's main compute thread
        // (pinned to a big core), so hinting its TID hits a real decode core.
        val hint = runCatching {
            getSystemService(android.os.PerformanceHintManager::class.java)
                ?.createHintSession(intArrayOf(android.os.Process.myTid()), TARGET_TOKEN_NANOS)
        }.getOrNull()
        var lastTokenNs = System.nanoTime()
        // Time-to-first-token: wall time from admission-return to the first streamed chunk.
        // This is where broker + scheduler + prefill cost shows up (steady-state decode tok/s
        // is measured separately), so it's the number to watch for scheduler overhead.
        val startNs = System.nanoTime()
        var firstTokenNs = 0L
        try {
            val started = System.currentTimeMillis()
            val sink = object : InferenceEngine.TokenSink {
                override fun onChunk(text: String) {
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    onToken(text)
                    // Report actual per-token work so the governor adapts its clock choice.
                    hint?.let {
                        val now = System.nanoTime()
                        runCatching { it.reportActualWorkDuration(now - lastTokenNs) }
                        lastTokenNs = now
                    }
                }
                override fun isCancelled(): Boolean = isCancelled()
            }
            engine.setSystemPrompt(session!!, system)   // apply the OpenAI system message (default "" = built-in)
            engine.setGrammar(session!!, grammar)        // constrain output if a grammar was set ("" = free)
            val n = engine.generate(session!!, sessionId, prompt, sink)
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > 0 && n > 0) lastTps = n * 1000.0 / elapsed
            val ttft = if (firstTokenNs > 0L) (firstTokenNs - startNs) / 1_000_000L else 0L
            EdgeLMHttpServer.GenStats(n, elapsed, ttft)
        } finally {
            runCatching { hint?.close() }                                    // release the hint session
            runCatching { android.os.Process.setThreadPriority(prevPrio) }   // restore worker prio
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
            // and defaults to BATCH priority (no UI foreground signal). Gated by the
            // same broker as Binder callers, via the loopback pseudo-identity (Part 7).
            infer = { _, system, prompt, grammar, onToken, isCancelled ->
                val d = broker.checkHttp(CapabilityBroker.Capability.CHAT)
                if (d is CapabilityBroker.Decision.Deny)
                    throw SecurityException("EdgeLM: ${d.reason}")
                dispatchInference("", prompt, AIScheduler.Priority.BATCH,
                    "OpenAI HTTP client", android.os.Process.myUid(), onToken, isCancelled, system, grammar)
            },
            warmModels = { warmModels() },
            edgeCatalog = { edgeCatalogJson() },
            edgePull = { model -> edgePull(model) },
            edgePin = { model, pinned -> edgePin(model, pinned) },
            edgeBatchedTest = {
                BatchedTest.run(applicationContext)
                org.json.JSONObject()
                    .put("status", "started")
                    .put("note", "watch logcat tag 'edgelm-batched-test'; needs a -DEDGELM_BATCHED build")
                    .toString()
            },
            edgeBatchedMode = { on -> setBatchedMode(on) },
            embeddings = { inputs -> edgeEmbeddings(inputs) },
            vectors = { op, body -> edgeVectors(op, body) },
            agent = { prompt -> edgeAgent(prompt) },
        ).also { server ->
            runCatching { server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onSuccess { Log.i(TAG, "HTTP shim on http://127.0.0.1:$HTTP_PORT/v1") }
                .onFailure { e -> Log.e(TAG, "HTTP shim failed to start", e) }
        }
    }

    // ---- Hub control surface for the CLI (Part 10/13) -------------------------

    /** Catalog JSON: every model + its installed/active/version/family/pinned state. */
    private fun edgeCatalogJson(): String {
        val active = ModelStore.activeId(this)
        val arr = org.json.JSONArray()
        ModelCatalog.models.forEach { spec ->
            arr.put(org.json.JSONObject()
                .put("id", spec.id)
                .put("name", spec.name)
                .put("params", spec.params)
                .put("size_mb", spec.sizeMb)
                .put("version", spec.version)
                .put("kind", spec.kind)
                .put("family", ai.edgelm.runtime.Hub.familyOf(spec))
                .put("installed", ModelStore.isInstalled(this, spec.id))
                .put("active", spec.id == active)
                .put("pinned_version", ai.edgelm.runtime.Hub.pinnedVersion(this, spec.id) ?: -1))
        }
        return org.json.JSONObject().put("models", arr).toString()
    }

    /** Resolve [model] (id / id@ver / family:…) via Hub, then enqueue a durable download. */
    private fun edgePull(model: String): String {
        val spec = ai.edgelm.runtime.Hub.resolve(this, model)
            ?: return org.json.JSONObject()
                .put("error", "could not resolve '$model' to a device-fit model").toString()
        val req = androidx.work.OneTimeWorkRequestBuilder<ai.edgelm.runtime.DownloadWorker>()
            .setInputData(androidx.work.workDataOf(ai.edgelm.runtime.DownloadWorker.KEY_ID to spec.id))
            .addTag(ai.edgelm.runtime.DownloadWorker.TAG_PREFIX + spec.id)
            .build()
        androidx.work.WorkManager.getInstance(this)
            .enqueueUniqueWork(ai.edgelm.runtime.DownloadWorker.UNIQUE,
                androidx.work.ExistingWorkPolicy.KEEP, req)
        return org.json.JSONObject()
            .put("id", spec.id).put("name", spec.name)
            .put("size_mb", spec.sizeMb).put("status", "enqueued").toString()
    }

    /** Pin/unpin [model] to its installed version (Hub rollback support). */
    private fun edgePin(model: String, pinned: Boolean): String {
        if (ModelCatalog.byId(model) == null)
            return org.json.JSONObject().put("error", "unknown model '$model'").toString()
        if (pinned) ai.edgelm.runtime.Hub.pin(this, model) else ai.edgelm.runtime.Hub.unpin(this, model)
        return org.json.JSONObject()
            .put("id", model).put("pinned", pinned)
            .put("pinned_version", ai.edgelm.runtime.Hub.pinnedVersion(this, model) ?: -1).toString()
    }

    // ---- Embeddings (Phase 2) -------------------------------------------------

    // A second resident model (the embedding encoder), independent of the chat model.
    @Volatile private var embedHandle: Long = 0
    private val embedLock = Any()   // the embedding context is single-threaded

    /** Lazily load the catalog's embedding model (if installed). Returns handle or 0. */
    @Synchronized private fun ensureEmbedModelLoaded(): Long {
        if (embedHandle != 0L) return embedHandle
        val spec = ModelCatalog.embeddingModel() ?: return 0
        val file = ModelStore.installedFile(this, spec.id) ?: return 0
        embedHandle = NativeBridge.loadEmbeddingModel(file.absolutePath)
        if (embedHandle != 0L) Log.i(TAG, "embedding model loaded: ${spec.id} (dim=${NativeBridge.embedDim(embedHandle)})")
        return embedHandle
    }

    /** OpenAI /v1/embeddings: gate EMBED, embed each input, return the response JSON. */
    private fun edgeEmbeddings(inputs: List<String>): String {
        val d = broker.checkHttp(CapabilityBroker.Capability.EMBED)
        if (d is CapabilityBroker.Decision.Deny)
            return org.json.JSONObject().put("error", "EdgeLM: ${d.reason}").toString()
        val h = ensureEmbedModelLoaded()
        if (h == 0L) return org.json.JSONObject()
            .put("error", "embedding model not installed — run: edgelm pull bge-small-en-v1.5").toString()

        val data = org.json.JSONArray()
        var approxTokens = 0
        synchronized(embedLock) {
            inputs.forEachIndexed { i, text ->
                val vec = NativeBridge.embed(h, text)
                    ?: return org.json.JSONObject().put("error", "embedding failed at index $i").toString()
                val arr = org.json.JSONArray()
                vec.forEach { arr.put(it.toDouble()) }
                data.put(org.json.JSONObject().put("object", "embedding").put("index", i).put("embedding", arr))
                approxTokens += text.length / 4
            }
        }
        return org.json.JSONObject()
            .put("object", "list")
            .put("data", data)
            .put("model", ModelCatalog.embeddingModel()?.id ?: "embedding")
            .put("usage", org.json.JSONObject().put("prompt_tokens", approxTokens).put("total_tokens", approxTokens))
            .toString()
    }

    // ---- Agent loop: in-runtime tool execution (Phase 2, MCP broker slice) ----

    /** Run one full generation synchronously and return the complete text. */
    private fun generateText(system: String, prompt: String, grammar: String = ""): String {
        val sb = StringBuilder()
        dispatchInference("", prompt, AIScheduler.Priority.BATCH, "agent",
            android.os.Process.myUid(), { sb.append(it) }, { false }, system, grammar)
        return sb.toString()
    }

    /** The agent loop: every step is a GRAMMAR-FORCED tool call (built-in tools + a
     *  `final_answer` pseudo-tool). Built-in tools are executed in-runtime and fed back;
     *  `final_answer` ends the loop. Grammar guarantees well-formed calls on any model. */
    private fun edgeAgent(userPrompt: String): String {
        val d = broker.checkHttp(CapabilityBroker.Capability.CHAT)
        if (d is CapabilityBroker.Decision.Deny) return errJson("EdgeLM: ${d.reason}")

        // Built-in tools + a final_answer tool, so every turn is a forced, valid tool call.
        val tools = ToolBroker.openAiTools()
        tools.put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "final_answer")
            .put("description", "Give the final answer to the user once you have what you need")
            .put("parameters", JSONObject("""{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""))))
        val system = ToolCalls.preamble(tools)
        val grammar = ToolCalls.grammarForTools(ToolCalls.toolNames(tools))   // forces a valid tool_call

        val steps = JSONArray()
        var convo = userPrompt
        var answer: String? = null
        for (step in 0 until 4) {                       // bounded tool loop
            val out = generateText(system, convo, grammar)
            val calls = ToolCalls.parse(out) ?: break
            val fn = calls.getJSONObject(0).getJSONObject("function")
            val name = fn.getString("name")
            val args = runCatching { JSONObject(fn.getString("arguments")) }.getOrDefault(JSONObject())
            if (name == "final_answer") { answer = args.optString("answer").ifBlank { convo }; break }
            val result = ToolBroker.execute(name, args)  // executed IN the runtime
            steps.put(JSONObject().put("tool", name).put("arguments", args).put("result", result))
            Log.i(TAG, "agent step $step: $name($args) -> $result")
            convo = "$userPrompt\n\n[The $name tool returned: $result]\n" +
                    "Now call final_answer with the answer for the user (include the result)."
        }
        return JSONObject()
            .put("answer", answer ?: "(no final answer after tool steps)")
            .put("steps", steps)
            .toString()
    }

    // ---- On-device vector index + RAG (Phase 2) -------------------------------

    private val vectorStore by lazy { VectorStore(File(filesDir, "vectors")) }

    private fun errJson(msg: String) = JSONObject().put("error", msg).toString()

    /** Embed one string via the resident embed model (single-threaded), or null. */
    private fun embedOne(text: String): FloatArray? {
        if (ensureEmbedModelLoaded() == 0L) return null
        return synchronized(embedLock) { NativeBridge.embed(embedHandle, text) }
    }

    /** /v1/edge/vectors/<op>: local semantic store + search, namespaced + EMBED-gated. */
    private fun edgeVectors(op: String, body: String): String {
        val d = broker.checkHttp(CapabilityBroker.Capability.EMBED)
        if (d is CapabilityBroker.Decision.Deny) return errJson("EdgeLM: ${d.reason}")
        val ns = CapabilityBroker.HTTP_PSEUDO_PACKAGE   // per-app isolation (loopback identity here)
        return try {
            when (op) {
                "upsert" -> {
                    val o = JSONObject(body)
                    val col = o.optString("collection", "default")
                    val items = o.optJSONArray("items") ?: JSONArray()
                    if (ensureEmbedModelLoaded() == 0L)
                        return errJson("embedding model not installed — run: edgelm pull bge-small-en-v1.5")
                    var n = 0
                    for (i in 0 until items.length()) {
                        val it = items.getJSONObject(i)
                        val text = it.optString("text"); if (text.isBlank()) continue
                        val id = it.optString("id").ifBlank { "doc-${System.nanoTime()}-$i" }
                        val meta = it.optJSONObject("metadata")?.toString() ?: ""
                        val vec = embedOne(text) ?: return errJson("embedding failed")
                        vectorStore.upsert(ns, col, id, text, meta, vec); n++
                    }
                    JSONObject().put("collection", col).put("upserted", n).toString()
                }
                "query" -> {
                    val o = JSONObject(body)
                    val col = o.optString("collection", "default")
                    val q = o.optString("query"); if (q.isBlank()) return errJson("missing 'query'")
                    if (ensureEmbedModelLoaded() == 0L)
                        return errJson("embedding model not installed — run: edgelm pull bge-small-en-v1.5")
                    val qv = embedOne(q) ?: return errJson("embedding failed")
                    val hits = vectorStore.query(ns, col, qv, o.optInt("top_k", 5))
                    val arr = JSONArray()
                    hits.forEach { arr.put(JSONObject().put("id", it.id).put("score", it.score.toDouble())
                        .put("text", it.text).put("metadata", it.meta)) }
                    JSONObject().put("collection", col).put("matches", arr).toString()
                }
                "delete" -> {
                    val o = JSONObject(body)
                    val col = o.optString("collection", "default")
                    val idsArr = o.optJSONArray("ids") ?: JSONArray()
                    val ids = (0 until idsArr.length()).map { idsArr.getString(it) }.toSet()
                    vectorStore.delete(ns, col, ids)
                    JSONObject().put("collection", col).put("deleted", ids.size).toString()
                }
                "collections" -> {
                    val arr = JSONArray()
                    vectorStore.collections(ns).forEach {
                        arr.put(JSONObject().put("name", it.first).put("count", it.second))
                    }
                    JSONObject().put("collections", arr).toString()
                }
                else -> errJson("unknown vectors op '$op'")
            }
        } catch (t: Throwable) { errJson(t.message ?: "vectors error") }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEdgeLMService.Stub() {

        override fun submit(model: String, sessionId: String, prompt: String,
                            priority: Int, callback: ITokenCallback): Long {
            val id = requestIds.getAndIncrement()
            val cancelFlag = AtomicBoolean(false)
            cancelled[id] = cancelFlag
            // Must read the caller's uid on the Binder thread, not the worker.
            val uid = Binder.getCallingUid()
            val caller = appLabel(uid)

            // ---- Permission gate (Part 7) -------------------------------------
            // Every request needs CHAT. Requests submitted at a non-foreground
            // priority additionally need BACKGROUND_INFERENCE — running inference
            // while not the foreground app is the battery-abuse vector we consent-gate.
            val prio = AIScheduler.Priority.of(priority)
            when (val d = broker.check(uid, CapabilityBroker.Capability.CHAT)) {
                is CapabilityBroker.Decision.Deny -> {
                    callback.onError("EdgeLM: ${d.reason}"); cancelled.remove(id); return id
                }
                is CapabilityBroker.Decision.NeedsConsent -> {
                    callback.onError("EdgeLM: consent required for '${d.capability.id}' — " +
                        "call EdgeLM.permissions().request(...)"); cancelled.remove(id); return id
                }
                CapabilityBroker.Decision.Allow -> { /* proceed */ }
            }
            if (prio == AIScheduler.Priority.BATCH || prio == AIScheduler.Priority.BACKGROUND) {
                val d = broker.check(uid, CapabilityBroker.Capability.BACKGROUND_INFERENCE)
                if (d !is CapabilityBroker.Decision.Allow) {
                    val msg = when (d) {
                        is CapabilityBroker.Decision.Deny -> d.reason
                        is CapabilityBroker.Decision.NeedsConsent ->
                            "consent required for 'background_inference' — call EdgeLM.permissions().request(...)"
                        else -> "background inference not permitted"
                    }
                    callback.onError("EdgeLM: $msg"); cancelled.remove(id); return id
                }
            }

            worker.execute {
                if (session == null && currentModelPath().isEmpty()) {
                    callback.onError("no model installed — open the EdgeLM Runtime app to download one")
                    cancelled.remove(id); return@execute
                }
                try {
                    val stats = dispatchInference(
                        sessionId,
                        prompt,
                        AIScheduler.Priority.of(priority),
                        caller,
                        uid,
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

        override fun hasCapability(capability: String?): Boolean {
            val cap = CapabilityBroker.Capability.byId(capability) ?: return false
            // UID resolved on the Binder thread — the caller can only ask about itself.
            return broker.isGranted(Binder.getCallingUid(), cap)
        }

        override fun capabilityNeedsConsent(capability: String?): Boolean =
            CapabilityBroker.Capability.byId(capability)?.risk == CapabilityBroker.Risk.HIGH
    }

    override fun onDestroy() {
        runCatching { http?.stop() }
        runCatching { batchedSession?.shutdown() }; batchedSession = null
        if (embedHandle != 0L) { runCatching { NativeBridge.unloadModel(embedHandle) }; embedHandle = 0 }
        cancelIdleUnload(); idleExecutor.shutdownNow()
        worker.shutdownNow()
        session?.let { engine.unload(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
