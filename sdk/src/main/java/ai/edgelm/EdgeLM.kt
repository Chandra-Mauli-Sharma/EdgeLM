package ai.edgelm

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.edgelm.internal.RuntimeConnection
import kotlinx.coroutines.flow.Flow

/** Thrown when the EdgeLM Runtime app is missing or doesn't respond — instead of hanging. */
class EdgeLMUnavailableException(message: String) : RuntimeException(message)

/**
 * EdgeLM public SDK — Phase 0.
 *
 * The entire point of this object is that an app author never sees Binder, AIDL,
 * mmap, or llama.cpp. They call [chat] and collect a Flow of tokens.
 *
 *     EdgeLM.initialize(context)
 *     EdgeLM.chat("llama-3.2-3b", "Hello").collect { print(it) }
 */
object EdgeLM {

    @Volatile private var connection: RuntimeConnection? = null

    /** Scheduling priority classes for [chat] — higher is admitted to the engine first. */
    const val FOREGROUND = 3
    const val INTERACTIVE = 2
    const val BATCH = 1
    const val BACKGROUND = 0

    private const val RUNTIME_PACKAGE = "ai.edgelm.runtime"

    /** Runtime availability on this device — check before [chat] to branch cleanly. */
    enum class Status { AVAILABLE, NOT_INSTALLED }

    /** Cheap presence check (no bind). Lets an app fall back or prompt install up front. */
    fun status(context: Context): Status =
        if (runCatching { context.packageManager.getPackageInfo(RUNTIME_PACKAGE, 0); true }.getOrDefault(false))
            Status.AVAILABLE else Status.NOT_INSTALLED

    /** Send the user to the runtime's store page (market:// with web fallback). Safe no-op on failure. */
    fun promptInstall(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$RUNTIME_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$RUNTIME_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }
            .onFailure { runCatching { context.startActivity(web) } }
    }

    /** Bind to the shared runtime service. Idempotent. */
    fun initialize(context: Context) {
        if (connection == null) {
            synchronized(this) {
                if (connection == null) {
                    connection = RuntimeConnection(context.applicationContext).also { it.bind() }
                }
            }
        }
    }

    /**
     * Stream a completion from the shared, on-device runtime.
     * Cold Flow: work starts on collect, cancels when the collector's scope cancels
     * (which propagates a Binder cancel() down to the decode loop).
     *
     * Pass a stable [sessionId] to continue a conversation: prior turns stay in the
     * warm KV cache and aren't re-prefilled. Default "" = stateless one-shot.
     */
    fun chat(
        model: String,
        prompt: String,
        sessionId: String = "",
        priority: Int = INTERACTIVE,
    ): Flow<String> {
        val conn = connection ?: error("Call EdgeLM.initialize(context) first")
        return conn.stream(model, sessionId, prompt, priority)
    }

    /** Models currently warm in the shared runtime (diagnostics). */
    suspend fun warmModels(): List<String> =
        (connection ?: error("not initialized")).warmModels()

    fun shutdown() {
        connection?.unbind()
        connection = null
    }
}
