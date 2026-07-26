package ai.edgelm.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible function/tool calling (arch doc Part 9, first slice).
 *
 * EdgeLM's small chat models aren't natively tool-trained, so we do it by prompt: the
 * tool schemas are formatted into a preamble that instructs the model to emit a single
 * JSON object when it wants to call a tool, and [parse] turns that back into the OpenAI
 * `tool_calls` shape. The app then executes the tool and sends the result back as a
 * `tool` message — the standard OpenAI loop. Executing tools *inside* the runtime (the
 * MCP plugin broker) is the larger follow-on.
 *
 * Pure JVM; lives in the HTTP shim path.
 */
object ToolCalls {

    /** Instruction + schemas prepended to the prompt when `tools` are present. */
    fun preamble(tools: JSONArray): String {
        val defs = JSONArray()
        for (i in 0 until tools.length()) {
            val f = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            defs.put(JSONObject()
                .put("name", f.optString("name"))
                .put("description", f.optString("description"))
                .put("parameters", f.opt("parameters") ?: JSONObject()))
        }
        // Small models follow this best when the directive is forceful and shows an example.
        val firstName = (0 until tools.length())
            .firstNotNullOfOrNull { tools.optJSONObject(it)?.optJSONObject("function")?.optString("name") }
            ?: "the_tool"
        return buildString {
            append("You are a function-calling assistant with these tools (JSON Schema):\n")
            append(defs.toString())
            append("\n\nWhen the request can be handled by a tool, you MUST reply with ONLY a JSON ")
            append("object in EXACTLY this form — no prose, no explanation, no markdown:\n")
            append("{\"tool_call\": {\"name\": \"tool_name\", \"arguments\": {\"arg\": \"value\"}}}\n\n")
            append("Example: if asked \"weather in Tokyo?\" and a get_weather(city) tool exists, reply:\n")
            append("{\"tool_call\": {\"name\": \"get_weather\", \"arguments\": {\"city\": \"Tokyo\"}}}\n\n")
            append("Prefer calling '$firstName' when it fits. Only if NO tool fits, answer in plain text.")
        }
    }

    /** All function names declared in a `tools` array. */
    fun toolNames(tools: JSONArray): List<String> =
        (0 until tools.length()).mapNotNull {
            tools.optJSONObject(it)?.optJSONObject("function")?.optString("name")?.ifBlank { null }
        }

    /**
     * GBNF grammar that FORCES the output to be `{"tool_call": {"name": <one of [names]>,
     * "arguments": <valid JSON object>}}`. Used when tool_choice requires a call — the model
     * then can't emit anything but a well-formed tool call, on any model size. Arguments are
     * constrained to valid JSON (not the per-tool schema — that's a later refinement using
     * llama.cpp's json-schema-to-grammar).
     */
    fun grammarForTools(names: List<String>): String {
        // Each alternative is the GBNF literal for the quoted tool name, e.g. "\"get_weather\"".
        val nameAlt = names.joinToString(" | ") { "\"\\\"" + it + "\\\"\"" }
        // GBNF close to llama.cpp's canonical json grammar. Avoids `.` (any-char), which
        // some GBNF versions reject — escapes are an explicit class instead.
        return """
            root   ::= "{" ws "\"tool_call\"" ws ":" ws "{" ws "\"name\"" ws ":" ws ( $nameAlt ) ws "," ws "\"arguments\"" ws ":" ws object ws "}" ws "}"
            object ::= "{" ws ( string ws ":" ws value ( ws "," ws string ws ":" ws value )* )? ws "}"
            array  ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
            value  ::= object | array | string | number | "true" | "false" | "null"
            string ::= "\"" ( [^"\\] | "\\" ["\\/bfnrtu] )* "\""
            number ::= "-"? ( "0" | [1-9] [0-9]* ) ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?
            ws     ::= [ \t\n]*
        """.trimIndent()
    }

    /**
     * Parse a model completion into OpenAI `tool_calls`, or null if it's plain content.
     * Tolerant of ```json fences and surrounding text; looks for a `{"tool_call": {...}}`.
     */
    fun parse(output: String): JSONArray? {
        var s = output.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
        }
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull() ?: return null
        val tc = obj.optJSONObject("tool_call") ?: return null
        val name = tc.optString("name"); if (name.isBlank()) return null

        // OpenAI requires `arguments` to be a JSON *string*. Small models often emit
        // "parameters" (the schema key) instead — accept it as an alias.
        val rawArgs = if (tc.has("arguments")) tc.opt("arguments") else tc.opt("parameters")
        val argStr = when (val a = rawArgs) {
            is JSONObject -> a.toString()
            is JSONArray -> a.toString()
            is String -> a
            null -> "{}"
            else -> a.toString()
        }
        val call = JSONObject()
            .put("id", "call_" + System.nanoTime())
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", argStr))
        return JSONArray().put(call)
    }
}
