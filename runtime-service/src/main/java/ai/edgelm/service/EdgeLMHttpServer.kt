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
 * SECURITY: loopback only, and the caller's app identity is lost here (unlike
 * Binder, which carries the UID). As of Phase 1 the [infer] callback routes through
 * the CapabilityBroker under a single loopback pseudo-identity (Part 7), so a denied
 * CHAT capability throws before any generation. The shim is DEBUG-only (see
 * EdgeLMService.startHttpShim) — release builds are Binder-only.
 */
class EdgeLMHttpServer(
    port: Int,
    private val infer: (model: String, prompt: String,
                        onToken: (String) -> Unit, isCancelled: () -> Boolean) -> GenStats,
    private val warmModels: () -> List<String>,
    // Hub control surface (arch doc Part 10/13) — each returns a JSON string.
    private val edgeCatalog: () -> String = { "{\"models\":[]}" },
    private val edgePull: (model: String) -> String = { "{\"error\":\"pull unavailable\"}" },
    private val edgePin: (model: String, pinned: Boolean) -> String = { _, _ -> "{\"error\":\"pin unavailable\"}" },
    private val edgeBatchedTest: () -> String = { "{\"error\":\"batched test unavailable\"}" },
    private val edgeBatchedMode: (on: Boolean) -> String = { "{\"error\":\"batched mode unavailable\"}" },
    // OpenAI /v1/embeddings: takes the input strings, returns the full response JSON.
    private val embeddings: (inputs: List<String>) -> String = { "{\"error\":\"embeddings unavailable\"}" },
    // On-device vector index: op = upsert|query|delete|collections, raw request body.
    private val vectors: (op: String, body: String) -> String = { _, _ -> "{\"error\":\"vectors unavailable\"}" },
) : NanoHTTPD("127.0.0.1", port) {

    data class GenStats(val tokenCount: Int, val elapsedMs: Long, val ttftMs: Long = 0)

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" ->
                    json(JSONObject().put("status", "ok").put("warm", JSONArray(warmModels())))

                session.method == Method.GET && session.uri == "/v1/models" ->
                    listModels()

                session.method == Method.POST && session.uri == "/v1/chat/completions" ->
                    chatCompletions(session)

                session.method == Method.POST && session.uri == "/v1/embeddings" -> {
                    val inputs = parseInputs(JSONObject(readBody(session)))
                    if (inputs.isEmpty()) badRequest("missing 'input'") else raw(embeddings(inputs))
                }

                // On-device vector index / RAG: /v1/edge/vectors/{upsert,query,delete,collections}
                session.uri.startsWith("/v1/edge/vectors/") -> {
                    val op = session.uri.removePrefix("/v1/edge/vectors/")
                    val body = if (session.method == Method.POST) readBody(session) else "{}"
                    raw(vectors(op, body))
                }

                // ---- Hub control surface (Part 10/13) ----
                session.method == Method.GET && session.uri == "/v1/edge/models" ->
                    raw(edgeCatalog())

                session.method == Method.POST && session.uri == "/v1/edge/pull" -> {
                    val model = JSONObject(readBody(session)).optString("model")
                    if (model.isBlank()) badRequest("missing 'model'") else raw(edgePull(model))
                }

                session.method == Method.POST && session.uri == "/v1/edge/pin" -> {
                    val req = JSONObject(readBody(session))
                    val model = req.optString("model")
                    if (model.isBlank()) badRequest("missing 'model'")
                    else raw(edgePin(model, req.optBoolean("pinned", true)))
                }

                // Increment 2 smoke test — kicks off BatchedTest; results stream to logcat.
                session.method == Method.POST && session.uri == "/v1/edge/batched-test" ->
                    raw(edgeBatchedTest())

                // Toggle the persistent batched engine for live requests.
                session.method == Method.POST && session.uri == "/v1/edge/batched-mode" ->
                    raw(edgeBatchedMode(JSONObject(readBody(session)).optBoolean("on", true)))

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
            .put("edge", JSONObject()                                       // EdgeLM extension
                .put("elapsed_ms", stats.elapsedMs)
                .put("ttft_ms", stats.ttftMs))                              // time-to-first-token
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

    /** OpenAI embeddings `input`: a single string or an array of strings → List<String>. */
    private fun parseInputs(req: JSONObject): List<String> {
        val arr = req.optJSONArray("input")
        if (arr != null) return (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        val s = req.optString("input", "")
        return if (s.isBlank()) emptyList() else listOf(s)
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)                 // for application/json, body -> "postData"
        var s = files["postData"] ?: "{}"
        // Strip a leading UTF-8 BOM (Windows PowerShell's `Set-Content -Encoding utf8`
        // prepends one, code point 0xFEFF), which otherwise breaks JSON parsing.
        if (s.isNotEmpty() && s[0].code == 0xFEFF) s = s.substring(1)
        return s.trim()
    }

    private fun json(obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())

    /** Return a pre-serialized JSON string (from the Hub control callbacks). */
    private fun raw(jsonStr: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", jsonStr)

    private fun badRequest(msg: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
            JSONObject().put("error", msg).toString())
}
