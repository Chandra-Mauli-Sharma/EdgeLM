package ai.edgelm.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.edgelm.service.EdgeLMService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-process progress bus. The download runs in a foreground service; the picker
 * Activity (same process) observes this to render per-card progress and reconcile
 * when reopened mid-download. Kept dead simple — one download at a time.
 */
object Downloads {
    @Volatile var activeId: String? = null; private set
    @Volatile var pct: Int = -1; private set
    @Volatile var read: Long = 0; private set
    @Volatile var total: Long = 0; private set
    @Volatile var lastError: String? = null; private set

    /** Set by the Activity while visible; invoked (on main thread) with the model id. */
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(id: String) { activeId = id; pct = -1; read = 0; total = 0; lastError = null; emit(id) }
    fun progress(id: String, pct: Int, read: Long, total: Long) {
        this.pct = pct; this.read = read; this.total = total; emit(id)
    }
    fun finish(id: String, error: String?) { activeId = null; lastError = error; emit(id) }

    private fun emit(id: String) { val l = listener; main.post { l?.invoke(id) } }
}

/**
 * Foreground service that downloads a model GGUF so the transfer survives the user
 * leaving the picker (the FGS keeps the process alive). Shows a progress
 * notification with a Cancel action, then flips the active model and asks the
 * runtime to (re)load it.
 */
class ModelDownloadService : Service() {

    companion object {
        const val EXTRA_ID = "model_id"
        const val ACTION_CANCEL = "ai.edgelm.download.CANCEL"
        private const val CHANNEL = "edgelm_download"
        private const val NOTIF_ID = 2408
        private const val TAG = "EdgeLMDownload"
    }

    @Volatile private var cancelled = false
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_ID)
        val spec = ModelCatalog.byId(id)
        if (spec == null) { stopSelf(); return START_NOT_STICKY }

        createChannel()
        startForegroundCompat(buildProgress(spec, -1, 0, 0))
        if (worker == null) {
            Downloads.start(spec.id)
            worker = Thread { runDownload(spec) }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(spec: ModelSpec) {
        val dest = ModelStore.fileFor(this, spec.id)
        val tmp = File(dest.parentFile, "${spec.id}.gguf.part")
        try {
            val c = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("User-Agent", "EdgeLM/1.0"); connect()
            }
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16); var read = 0L; var lastPct = -1; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        if (cancelled) throw InterruptedException("cancelled")
                        out.write(buf, 0, n); read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        Downloads.progress(spec.id, pct, read, total)
                        if (pct != lastPct) {
                            lastPct = pct
                            notify(buildProgress(spec, pct, read, total))
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("could not finalize file")

            ModelStore.setActive(this, spec.id)                 // newly downloaded => active
            runCatching {                                       // ask the runtime to load it
                startService(Intent(this, EdgeLMService::class.java)
                    .setAction("ai.edgelm.action.LOAD"))        // = EdgeLMService.ACTION_LOAD
            }
            Downloads.finish(spec.id, null)
            finishNotification(spec, ok = true)
            Log.i(TAG, "downloaded ${spec.id}")
        } catch (t: Throwable) {
            tmp.delete()
            val msg = if (cancelled) "cancelled" else (t.message ?: "download failed")
            Downloads.finish(spec.id, msg)
            if (cancelled) cancelNotification() else finishNotification(spec, ok = false)
            Log.w(TAG, "download ${spec.id} ended: $msg")
        } finally {
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    // ---- Notification ---------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Progress while downloading an AI model."; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildProgress(spec: ModelSpec, pct: Int, read: Long, total: Long): Notification {
        val text = if (pct >= 0) "$pct%   ·   ${mb(read)} / ${mb(total)} MB"
                   else "Starting…"
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edgelm)
            .setContentTitle("Downloading ${spec.name}")
            .setContentText(text)
            .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF9BFF3C.toInt())
            .addAction(0, "Cancel", cancel)
            .setContentIntent(openApp())
            .build()
    }

    private fun finishNotification(spec: ModelSpec, ok: Boolean) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edgelm)
            .setContentTitle(spec.name)
            .setContentText(if (ok) "Downloaded & active ✓" else "Download failed")
            .setOngoing(false).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF9BFF3C.toInt())
            .setContentIntent(openApp())
            .build()
        notify(n)
    }

    private fun cancelNotification() =
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, RuntimeActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notify(n: Notification) =
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }

    private fun startForegroundCompat(n: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        }
    }

    private fun mb(b: Long) = if (b < 0) "?" else (b / 1_048_576).toString()
}
