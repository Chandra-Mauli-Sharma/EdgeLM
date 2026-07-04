package ai.edgelm.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import ai.edgelm.contract.IEdgeLMService
import ai.edgelm.contract.ITokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Manages the Binder connection to the runtime-service and adapts the
 * callback-based AIDL into idiomatic Kotlin coroutines/Flow.
 */
internal class RuntimeConnection(private val context: Context) {

    // Explicit component so we bind exactly the EdgeLM runtime, nothing else.
    private val runtimeComponent = ComponentName(
        "ai.edgelm.runtime",
        "ai.edgelm.service.EdgeLMService"
    )

    @Volatile private var service: IEdgeLMService? = null
    private val onConnected = CopyOnWriteArrayList<(IEdgeLMService) -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IEdgeLMService.Stub.asInterface(binder)
            service = s
            onConnected.forEach { it(s) }
            onConnected.clear()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null   // Phase 1: auto-rebind + replay in-flight requests
        }
    }

    fun bind() {
        val intent = Intent().setComponent(runtimeComponent)
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { context.unbindService(conn) }
        service = null
    }

    private suspend fun awaitService(): IEdgeLMService =
        service ?: suspendCancellableCoroutine { cont ->
            onConnected.add { cont.resume(it) }
        }

    /** Turn one AIDL streaming request into a cold Flow<String>. */
    fun stream(model: String, prompt: String): Flow<String> = callbackFlow {
        val svc = awaitService()
        val cb = object : ITokenCallback.Stub() {
            override fun onTokens(chunk: String) { trySend(chunk) }
            override fun onDone(tokenCount: Int, elapsedMs: Long) { close() }
            override fun onError(message: String) { close(RuntimeException(message)) }
        }
        val requestId = svc.submit(model, prompt, cb)

        // When the collector cancels, propagate all the way to the decode loop.
        awaitClose { runCatching { svc.cancel(requestId) } }
    }

    suspend fun warmModels(): List<String> =
        awaitService().warmModels().toList()
}
