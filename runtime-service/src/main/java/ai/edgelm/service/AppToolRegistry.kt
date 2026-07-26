package ai.edgelm.service

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * External (app-provided) tools the agent can call — the MCP-server host model (arch doc
 * Part 9). An app registers a tool as an HTTP webhook: {name, description, parameters, url}.
 * When the model calls it, the runtime POSTs the arguments to the webhook and feeds the
 * result back into the agent loop. This is how EdgeLM becomes a *platform* — apps extend
 * the agent's abilities without the runtime shipping every tool.
 *
 * Registered tools are treated as side-effecting (they hit the network / act), so the agent
 * only runs them with explicit consent (allow_side_effects). Pure JVM.
 *
 * SECURITY (noted, not yet enforced): the runtime calling arbitrary registered URLs is the
 * network-egress surface the arch-doc data-flow firewall (Part 7/9) governs — taint-tracking
 * local data so it can't be laundered to an external tool. That firewall is the follow-on;
 * for now registration is a trusted, local (loopback) operation.
 */
object AppToolRegistry {

    private const val TAG = "edgelm-tools"

    data class RegisteredTool(val name: String, val description: String, val parameters: JSONObject, val url: String)

    private val tools = ConcurrentHashMap<String, RegisteredTool>()

    fun register(name: String, description: String, parameters: JSONObject, url: String) {
        tools[name] = RegisteredTool(name, description, parameters, url)
        Log.i(TAG, "registered tool '$name' -> $url")
    }

    fun unregister(name: String) { tools.remove(name) }

    fun byName(name: String): RegisteredTool? = tools[name]

    fun all(): List<RegisteredTool> = tools.values.toList()

    /** Registered tools as an OpenAI `tools` array (to merge into the agent's tool set). */
    fun openAiTools(): JSONArray {
        val a = JSONArray()
        tools.values.forEach {
            a.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", it.name).put("description", it.description).put("parameters", it.parameters)))
        }
        return a
    }

    /** Call the tool's webhook with [args]; returns its result text (or an error string). */
    fun execute(name: String, args: JSONObject): String {
        val t = byName(name) ?: return "error: unknown tool '$name'"
        return try {
            val c = (URL(t.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(JSONObject().put("arguments", args).toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty().trim()
            if (code in 200..299) {
                // Accept either {"result": "..."} or a plain string body.
                runCatching { JSONObject(body).optString("result", body) }.getOrDefault(body)
            } else "error: tool '$name' returned HTTP $code"
        } catch (e: Exception) { "error: calling '$name': ${e.message}" }
    }
}
