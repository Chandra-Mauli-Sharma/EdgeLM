package ai.edgelm.service

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI-compatible HTTP shim for the EdgeLM runtime.
 *
 * Runs INSIDE the runtime-service process, bound to 127.0.0.1, so it calls the
 * shared engine directly (no Binder hop). This is the compatibility surface from
 * Part 5 of the architecture doc: existing OpenAI SDKs / cURL work unmodified by
 * pointing base_url at http://localhost:1408/v1.
 *
 * Endpoints:
 *   POST /v1/chat/completions   (stream=true -> SSE, else one JSON object)
 *   GET  /v1/models
 *   GET  /health
 *
 * SECURITY (spike-level): loopback only, no auth, and the caller's app identity
 * is lost here (unlike Binder, which carries the UID). Phase 1+ gates this behind
 * the permission broker; for now it's a localhost dev convenience.
 */
class EdgeLMHttpServer(
    port: Int,
    private val infer: (model: String, prompt: String,
                        onToken: (String) -> Unit, isCancelled: () -> Boolean) -> GenStats,
    private val warmModels: () -> List<String>,
) : NanoHTTPD("127.0.0.1", port) {

    data class GenStats(val tokenCount: Int, val elapsedMs: Long)

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" ->
                    json(JSONObject().put("status", "ok").put("warm", JSONArray(warmModels())))

                session.method == Method.GET && session.uri == "/v1/models" ->
                    listModels()

                session.method == Method.POST && session.uri == "/v1/chat/completions" ->
                    chatCompletions(session)

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "application/json",
                    JSONObject().put("error", "not found: ${session.uri}").toString()
                )
            }
        } catch (t: Throwable) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", t.message ?: "internal error").toString()
            )
        }
    }

    // ---- /v1/models ---------------------------------------------------------
    private fun listModels(): Response {
        val data = JSONArray()
        warmModels().forEach { id ->
            data.put(JSONObject().put("id", id).put("object", "model").put("owned_by", "edgelm"))
        }
        return json(JSONObject().put("object", "list").put("data", data))
    }

    // ---- /v1/chat/completions ----------------------------------------------
    private fun chatCompletions(session: IHTTPSession): Response {
        val body = readBody(session)
        val req = JSONObject(body)
        val model = req.optString("model", "default")
        val stream = req.optBoolean("stream", false)
        val prompt = flattenMessages(req.optJSONArray("messages"))

        return if (stream) streamingResponse(model, prompt) else blockingResponse(model, prompt)
    }

    /** SSE: one chat.completion.chunk per token, then [DONE]. */
    private fun streamingResponse(model: String, prompt: String): Response {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        val id = "chatcmpl-" + System.nanoTime()
        val cancelled = AtomicBoolean(false)

        Thread {
            try {
                infer(model, prompt,
                    { token ->
                        val chunk = JSONObject()
                            .put("id", id).put("object", "chat.completion.chunk")
                            .put("model", model)
                            .put("choices", JSONArray().put(
                                JSONObject().put("index", 0)
                                    .put("delta", JSONObject().put("content", token))
                            ))
                        pipeOut.write("data: $chunk\n\n".toByteArray())
                        pipeOut.flush()
                    },
                    { cancelled.get() })
                pipeOut.write("data: [DONE]\n\n".toByteArray())
                pipeOut.flush()
            } catch (_: Throwable) {
                // client hung up mid-stream -> stop generating
                cancelled.set(true)
            } finally {
                runCatching { pipeOut.close() }
            }
        }.apply { isDaemon = true; start() }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    /** Non-streaming: accumulate, return a single chat.completion object. */
    private fun blockingResponse(model: String, prompt: String): Response {
        val sb = StringBuilder()
        val stats = infer(model, prompt, { sb.append(it) }, { false })
        val payload = JSONObject()
            .put("id", "chatcmpl-" + System.nanoTime())
            .put("object", "chat.completion")
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0)
                    .put("message", JSONObject().put("role", "assistant").put("content", sb.toString()))
                    .put("finish_reason", "stop")
            ))
            .put("usage", JSONObject()
                .put("completion_tokens", stats.tokenCount)
                .put("total_tokens", stats.tokenCount))
            .put("edge", JSONObject().put("elapsed_ms", stats.elapsedMs))  // EdgeLM extension
        return json(payload)
    }

    // ---- helpers ------------------------------------------------------------
    private fun flattenMessages(messages: JSONArray?): String {
        if (messages == null) return ""
        // Spike: concatenate turns into one prompt; the native layer applies the
        // chat template. Phase 1 passes structured turns through instead.
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            sb.append(m.optString("role")).append(": ")
              .append(m.optString("content")).append('\n')
        }
        return sb.toString().trim()
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)                 // for application/json, body -> "postData"
        var s = files["postData"] ?: "{}"
        // Strip a leading UTF-8 BOM (Windows PowerShell's `Set-Content -Encoding utf8`
        // prepends one, code point 0xFEFF), which otherw