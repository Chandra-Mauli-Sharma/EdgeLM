package ai.edgelm

import android.content.Context
import ai.edgelm.internal.RuntimeConnection
import kotlinx.coroutines.flow.Flow

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
     */
    fun chat(model: String, prompt: String): Flow<String> {
        val conn = connection ?: error("Call EdgeLM.initialize(context) first")
        return conn.stream(model, prompt)
    }

    /** Models currently warm in the shared runtime (diagnostics). */
    suspend fun warmModels(): List<String> =
        (connection ?: error("not initialized")).warmModels()

    fun shutdown() {
        connection?.unbind()
        connection = null
    }
}
