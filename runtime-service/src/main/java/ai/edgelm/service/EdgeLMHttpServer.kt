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
    private val infer: (model: String, system: String, prompt: String, grammar: String,
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
    // Agent loop: raw body {prompt, allow_side_effects} -> {answer, steps}.
    private val agent: (body: String) -> String = { "{\"error\":\"agent unavailable\"}" },
    // Retrieval-augmented chat: raw body {collection, query, top_k} -> {answer, sources}.
    private val rag: (body: String) -> String = { "{\"error\":\"rag unavailable\"}" },
    // Vision: raw body {image (base64), prompt} -> {caption}.
    private val caption: (body: String) -> String = { "{\"error\":\"vision unavailable\"}" },
    // Speech: raw body {audio (base64), prompt} -> {text}.
    private val transcribe: (body: String) -> String = { "{\"error\":\"speech unavailable\"}" },
    // App tool registry: op = register|unregister|list, raw body.
    private val appTools: (op: String, body: String) -> String = { _, _ -> "{\"error\":\"tools unavailable\"}" },
    // Egress firewall policy: op = list|allow|deny|allow-tainted|deny-tainted|forget, raw body.
    private val egress: (op: String, body: String) -> String = { _, _ -> "{\"error\":\"egress policy unavailable\"}" },
    // Capability grants: op = list|grant|deny|revoke, raw body.
    private val permissions: (op: String, body: String) -> String = { _, _ -> "{\"error\":\"permissions unavailable\"}" },
    // Make a model active for subsequent inference.
    private val activate: (model: String) -> String = { "{\"error\":\"activate unavailable\"}" },
    // Live model-download status (WorkManager progress).
    private val downloads: () -> String = { "{\"downloads\":[]}" },
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

                // Agent loop with in-runtime tool execution.
                session.method == Method.POST && session.uri == "/v1/edge/agent" ->
                    raw(agent(readBody(session)))

                // Retrieval-augmented chat over a local vector collection.
                session.method == Method.POST && session.uri == "/v1/edge/rag" ->
                    raw(rag(readBody(session)))

                // Vision: caption / describe an image.
                session.method == Method.POST && session.uri == "/v1/edge/caption" ->
                    raw(caption(readBody(session)))

                // Speech: transcribe audio.
                session.method == Method.POST && session.uri == "/v1/edge/transcribe" ->
                    raw(transcribe(readBody(session)))

                // App tool registry (external webhook tools for the agent).
                session.method == Method.GET && session.uri == "/v1/edge/tools" ->
                    raw(appTools("list", ""))
                session.method == Method.POST && session.uri.startsWith("/v1/edge/tools/") ->
                    raw(appTools(session.uri.removePrefix("/v1/edge/tools/"), readBody(session)))

                // Egress firewall policy: /v1/edge/egress (GET list) or /v1/edge/egress/{op}.
                session.method == Method.GET && session.uri == "/v1/edge/egress" ->
                    raw(egress("list", ""))
                session.method == Method.POST && session.uri.startsWith("/v1/edge/egress/") ->
                    raw(egress(session.uri.removePrefix("/v1/edge/egress/"), readBody(session)))

                // Capability grants: /v1/edge/permissions (GET list) or /v1/edge/permissions/{op}.
                session.method == Method.GET && session.uri == "/v1/edge/permissions" ->
                    raw(permissions("list", ""))
                session.method == Method.POST && session.uri.startsWith("/v1/edge/permissions/") ->
                    raw(permissions(session.uri.removePrefix("/v1/edge/permissions/"), readBody(session)))

                // ---- Hub control surface (Part 10/13) ----
                session.method == Method.GET && session.uri == "/v1/edge/models" ->
                    raw(edgeCatalog())

                session.method == Method.POST && session.uri == "/v1/edge/pull" -> {
                    val model = JSONObject(readBody(session)).optString("model")
                    if (model.isBlank()) badRequest("missing 'model'") else raw(edgePull(model))
                }

                session.method == Method.POST && session.uri == "/v1/edge/activate" -> {
                    val model = JSONObject(readBody(session)).optString("model")
                    if (model.isBlank()) badRequest("missing 'model'") else raw(activate(model))
                }

                session.method == Method.GET && session.uri == "/v1/edge/downloads" ->
                    raw(downloads())

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
        val messages = req.optJSONArray("messages")
        val system = extractSystem(messages)            // the OpenAI system message(s)
        val prompt = flattenMessages(messages)          // user/assistant turns only

        // Tool/function calling: put the tool schemas in the SYSTEM prompt (most reliable),
        // then (non-streaming) parse the model's reply into OpenAI tool_calls. When
        // tool_choice forces a call, constrain output with a GBNF grammar so the tool call
        // is GUARANTEED well-formed on any model.
        val tools = req.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val toolSystem = (if (system.isNotBlank()) "$system\n\n" else "") + ToolCalls.preamble(tools)
            val grammar = grammarFor(req.opt("tool_choice"), tools)
            return toolResponse(model, toolSystem, prompt, grammar)
        }

        return if (stream) streamingResponse(model, system, prompt)
               else blockingResponse(model, system, prompt)
    }

    /** Resolve OpenAI tool_choice → a forcing GBNF grammar, or "" for auto/none. */
    private fun grammarFor(toolChoice: Any?, tools: JSONArray): String {
        val forced: List<String> = when {
            toolChoice is String && toolChoice == "required" -> ToolCalls.toolNames(tools)
            toolChoice is JSONObject && toolChoice.optString("type") == "function" ->
                listOfNotNull(toolChoice.optJSONObject("function")?.optString("name")?.ifBlank { null })
            else -> emptyList()   // "auto" (default) / "none" / absent → model decides
        }
        return if (forced.isNotEmpty()) ToolCalls.grammarForTools(forced) else ""
    }

    /** Non-streaming completion that returns OpenAI tool_calls if the model emitted one. */
    private fun toolResponse(model: String, system: String, prompt: String, grammar: String): Response {
        val sb = StringBuilder()
        val stats = infer(model, system, prompt, grammar, { sb.append(it) }, { false })
        val calls = ToolCalls.parse(sb.toString())
        val msg = JSONObject().put("role", "assistant")
        val finish: String
        if (calls != null) { msg.put("content", JSONObject.NULL).put("tool_calls", calls); finish = "tool_calls" }
        else { msg.put("content", sb.toString()); finish = "stop" }
        val payload = JSONObject()
            .put("id", "chatcmpl-" + System.nanoTime())
            .put("object", "chat.completion")
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("message", msg).put("finish_reason", finish)))
            .put("usage", JSONObject()
                .put("completion_tokens", stats.tokenCount).put("total_tokens", stats.tokenCount))
            .put("edge", JSONObject().put("elapsed_ms", stats.elapsedMs).put("ttft_ms", stats.ttftMs))
        return json(payload)
    }

    /** SSE: one chat.completion.chunk per token, then [DONE]. */
    private fun streamingResponse(model: String, system: String, prompt: String): Response {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        val id = "chatcmpl-" + System.nanoTime()
        val cancelled = AtomicBoolean(false)

        Thread {
            try {
                infer(model, system, prompt, "",
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
    private fun blockingResponse(model: String, system: String, prompt: String): Response {
        val sb = StringBuilder()
        val stats = infer(model, system, prompt, "", { sb.append(it) }, { false })
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
        // Concatenate the user/assistant turns into one prompt; the native layer applies the
        // chat template. System messages are handled separately (see extractSystem).
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            sb.append(role).append(": ").append(m.optString("content")).append('\n')
        }
        return sb.toString().trim()
    }

    /** Concatenate the content of any system messages → the native system prompt. */
    private fun extractSystem(messages: JSONArray?): String {
        if (messages == null) return ""
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") == "system") {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(m.optString("content"))
            }
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
