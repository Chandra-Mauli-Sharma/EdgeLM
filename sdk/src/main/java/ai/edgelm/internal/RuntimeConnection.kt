package ai.edgelm.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import ai.edgelm.EdgeLMUnavailableException
import ai.edgelm.contract.IEdgeLMService
import ai.edgelm.contract.ITokenCallback
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Manages the Binder connection to the runtime-service and adapts the
 * callback-based AIDL into coroutines/Flow.
 *
 * Distribution-safe: if the runtime app is absent or never binds, we DON'T hang —
 * we detect it and throw [EdgeLMUnavailableException] so callers can fall back or
 * prompt the user to install the runtime.
 */
internal class RuntimeConnection(private val context: Context) {

    companion object {
        const val RUNTIME_PACKAGE = "ai.edgelm.runtime"
        private const val RUNTIME_CLASS = "ai.edgelm.service.EdgeLMService"
        private const val BIND_TIMEOUT_MS = 8_000L   // model loads in onCreate (~2s); allow slack
    }

    private val runtimeComponent = ComponentName(RUNTIME_PACKAGE, RUNTIME_CLASS)

    @Volatile private var service: IEdgeLMService? = null
    @Volatile private var failed = false
    private val waiters = CopyOnWriteArrayList<CancellableContinuation<IEdgeLMService?>>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IEdgeLMService.Stub.asInterface(binder)
            service = s
            drain { it.resume(s) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
        override fun onNullBinding(name: ComponentName?) { failed = true; drain { it.resume(null) } }
        override fun onBindingDied(name: ComponentName?)  { service = null; failed = true; drain { it.resume(null) } }
    }

    private inline fun drain(action: (CancellableContinuation<IEdgeLMService?>) -> Unit) {
        val snapshot = waiters.toList(); waiters.clear(); snapshot.forEach(action)
    }

    /** Is the runtime app installed at all? (Cheap PackageManager check.) */
    fun isRuntimeInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(RUNTIME_PACKAGE, 0); true
    }.getOrDefault(false)

    /** Attempt to bind. Returns false immediately if the runtime isn't installed / bind is refused. */
    fun bind(): Boolean {
        if (!isRuntimeInstalled()) { failed = true; return false }
        val ok = runCatching {
            context.bindService(Intent().setComponent(runtimeComponent), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) failed = true
        return ok
    }

    fun unbind() {
        runCatching { context.unbindService(conn) }
        service = null
    }

    /** Suspend until connected, or throw [EdgeLMUnavailableException] on absence/timeout. */
    private suspend fun awaitService(): IEdgeLMService {
        service?.let { return it }
        val svc = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                service?.let { cont.resume(it); return@suspendCancellableCoroutine }
                if (failed)  { cont.resume(null); return@suspendCancellableCoroutine }
                waiters.add(cont)
                cont.invokeOnCancellation { waiters.remove(cont) }
            }
        }
        return svc ?: throw EdgeLMUnavailableException(
            if (!isRuntimeInstalled())
                "EdgeLM Runtime is not installed on this device."
            else
                "EdgeLM Runtime did not respond within ${BIND_TIMEOUT_MS}ms."
        )
    }

    /** Turn one AIDL streaming request into a cold Flow<String>. */
    fun stream(model: String, sessionId: String, prompt: String, priority: Int): Flow<String> = callbackFlow {
        val svc = awaitService()   // throws EdgeLMUnavailableException instead of hanging
        val cb = object : ITokenCallback.Stub() {
            override fun onTokens(chunk: String) { trySend(chunk) }
            override fun onDone(tokenCount: Int, elapsedMs: Long) { close() }
            override fun onError(message: String) { close(RuntimeException(message)) }
        }
        val requestId = svc.submit(model, sessionId, prompt, priority, cb)
        awaitClose { runCatching { svc.cancel(requestId) } }
    }

    suspend fun warmModels(): List<String> = awaitService().warmModels().toList()
}
