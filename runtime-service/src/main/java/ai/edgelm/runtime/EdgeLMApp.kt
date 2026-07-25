package ai.edgelm.runtime

import android.app.Application
import android.util.Log
import androidx.work.Configuration

/**
 * Application class whose sole job is to make WorkManager initialize **on demand in any
 * process**, not just the default one.
 *
 * The runtime service runs in a separate process (`:core`), and androidx.startup's
 * `InitializationProvider` (which auto-initializes WorkManager) only runs in the main
 * process. So when the shim's `/v1/edge/pull` — which lives in `:core` — enqueues a
 * DownloadWorker, `WorkManager.getInstance()` throws "not initialized". Implementing
 * [Configuration.Provider] here (plus removing the default initializer in the manifest)
 * lets WorkManager lazily initialize itself the first time any process asks for it, so
 * both the main-process UI and the `:core` service can enqueue downloads.
 *
 * WorkManager still runs the workers in a single default process and coordinates via its
 * DB, so enqueuing from `:core` and observing from the UI both work.
 */
class EdgeLMApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
