package ai.edgelm.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.edgelm.service.DeviceProfile
import ai.edgelm.service.EdgeLMService
import ai.edgelm.service.EngineRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a model GGUF as durable background work. WorkManager persists the job,
 * so it survives the app closing or the process being killed — on relaunch the UI
 * reconciles from the persisted WorkInfo. Runs as a foreground (dataSync) worker
 * with a progress notification and a reliable Cancel action.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val UNIQUE = "model-download"     // unique work name (one download at a time)
        const val KEY_ID = "id"
        const val KEY_PCT = "pct"
        const val KEY_READ = "read"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val TAG_PREFIX = "model:"
        private const val CHANNEL = "edgelm_download"
        private const val NOTIF_ID = 2408
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val spec = ModelCatalog.byId(id) ?: return@withContext Result.failure()

        // Pick the artifact the SAME way the engine will be picked to run it: the router
        // resolves this device's engine, and that engine names the URL + format to fetch.
        // A GPU-capable device with a .litertlm model fetches that; otherwise the GGUF.
        EngineRegistry.init(applicationContext)   // LiteRT engine needs a Context
        val engine = EngineRegistry.select(spec, DeviceProfile.current())
        val artifact = engine.artifactFor(spec) ?: EngineRegistry.fallback().artifactFor(spec)
            ?: return@withContext Result.failure(workDataOf(KEY_ID to id, KEY_ERROR to "no downloadable artifact for this device"))

        createChannel()
        setForeground(foregroundInfo(spec, -1, 0, 0))

        val dest = ModelStore.fileFor(applicationContext, spec.id, artifact.format)
        val tmp = File(dest.parentFile, "${spec.id}.${artifact.format}.part")
        try {
            // Resume: if a partial file exists, ask the server (HTTP Range) to continue
            // from where we stopped instead of restarting a multi-hundred-MB download.
            val have = if (tmp.exists()) tmp.length() else 0L
            val c = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30000; readTimeout = 90000   // generous — mobile + large files
                setRequestProperty("User-Agent", "EdgeLM/1.0")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
                connect()
            }
            val code = c.responseCode
            // Genuine client errors (bad URL / gone) won't fix themselves — fail fast.
            if (code in 400..499 && code != 408 && code != 429) {
                tmp.delete()
                return@withContext Result.failure(workDataOf(KEY_ID to id, KEY_ERROR to "server error $code"))
            }
            val resuming = have > 0 && code == HttpURLConnection.HTTP_PARTIAL   // 206
            var read = if (resuming) have else 0L
            val total = read + c.contentLengthLong
            c.inputStream.use { input ->
                FileOutputStream(tmp, /*append=*/resuming).use { out ->
                    val buf = ByteArray(1 shl 16); var lastPct = -1; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        if (isStopped) { tmp.delete(); return@withContext Result.failure() } // cancelled
                        out.write(buf, 0, n); read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        setProgress(workDataOf(KEY_ID to id, KEY_PCT to pct, KEY_READ to read, KEY_TOTAL to total))
                        if (pct != lastPct) {
                            lastPct = pct
                            notify(foregroundInfo(spec, pct, read, total).notification)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("could not finalize file")

            ModelStore.setActive(applicationContext, spec.id)          // newly downloaded => active
            runCatching {                                              // best-effort warm the runtime
                applicationContext.startService(
                    Intent(applicationContext, EdgeLMService::class.java).setAction("ai.edgelm.action.LOAD")
                )
            }
            Result.success(workDataOf(KEY_ID to id))
        } catch (t: Throwable) {
            when {
                isStopped -> { tmp.delete(); Result.failure() }        // user cancelled
                // Transient (timeout/stall/dropped connection): keep the .part and let
                // WorkManager retry with backoff — the next run resumes via Range.
                runAttemptCount < 6 -> Result.retry()
                else -> { tmp.delete(); Result.failure(workDataOf(KEY_ID to id, KEY_ERROR to (t.message ?: "download failed"))) }
            }
        }
    }

    // ---- foreground notification ---------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Progress while downloading an AI model."; setShowBadge(false) }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun foregroundInfo(spec: ModelSpec, pct: Int, read: Long, total: Long): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, RuntimeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (pct >= 0) "$pct%   ·   ${mb(read)} / ${mb(total)} MB" else "Starting…"
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edgelm)
            .setContentTitle("Downloading ${spec.name}")
            .setContentText(text)
            .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF9BFF3C.toInt())
            .addAction(0, "Cancel", cancel)
            .setContentIntent(open)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun notify(n: Notification) = runCatching {
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun mb(b: Long) = if (b < 0) "?" else (b / 1_048_576).toString()
}
