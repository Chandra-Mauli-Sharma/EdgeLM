package ai.edgelm.service

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * First slice of the plugin/tool broker (arch doc Part 9). Where [ToolCalls] returns a
 * tool call for the *app* to run, this executes registered tools **inside the runtime**
 * and feeds the result back to the model — the agent loop. This slice ships a couple of
 * safe, permissionless built-in tools; sandboxed third-party plugins + MCP servers +
 * consent + the data-flow firewall are the larger follow-on.
 *
 * Pure JVM. No I/O, no reflection, no code-eval — the calculator is a hand-written
 * arithmetic parser, so a tool call can never execute arbitrary code.
 */
object ToolBroker {

    data class Tool(
        val name: String,
        val description: String,
        val parameters: JSONObject,
        // Read tools auto-run; side-effecting ones (write/act) need explicit consent
        // (arch doc Part 9: "write/actuating tools require a confirmation surface").
        val sideEffecting: Boolean = false,
        val run: (JSONObject) -> String,
    )

    // Where the `remember` tool writes (set by the service; null until init).
    @Volatile private var notesFile: File? = null
    fun init(filesDir: File) { notesFile = File(filesDir, "agent_notes.txt") }

    private val tools: List<Tool> = listOf(
        Tool(
            name = "calculator",
            description = "Evaluate an arithmetic expression with + - * / and parentheses, e.g. (2+3)*4",
            parameters = JSONObject("""{"type":"object","properties":{"expression":{"type":"string","description":"the expression"}},"required":["expression"]}"""),
            run = { args ->
                val e = args.optString("expression")
                runCatching {
                    val v = Calc.eval(e)
                    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                }.getOrElse { "error: ${it.message}" }
            },
        ),
        Tool(
            name = "current_time",
            description = "Get the current local date and time",
            parameters = JSONObject("""{"type":"object","properties":{}}"""),
            run = { _ -> SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US).format(Date()) },
        ),
        Tool(
            name = "remember",
            description = "Save a short note on this device for the user to recall later",
            parameters = JSONObject("""{"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}"""),
            sideEffecting = true,   // writes to disk → consent-gated
            run = { args ->
                val note = args.optString("note")
                val f = notesFile ?: return@Tool "error: notes storage unavailable"
                runCatching { f.appendText(note + "\n"); "saved" }.getOrElse { "error: ${it.message}" }
            },
        ),
    )

    fun byName(name: String): Tool? = tools.firstOrNull { it.name == name }

    /** The built-in tools as an OpenAI `tools` array (to inject into an agent request). */
    fun openAiTools(): JSONArray {
        val a = JSONArray()
        tools.forEach {
            a.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", it.name).put("description", it.description).put("parameters", it.parameters)))
        }
        return a
    }

    /** Execute a tool by name with JSON [args]; returns its result text (or an error string). */
    fun execute(name: String, args: JSONObject): String =
        byName(name)?.run?.invoke(args) ?: "error: unknown tool '$name'"

    /** Safe arithmetic evaluator (recursive descent) — no code execution. */
    private object Calc {
        fun eval(expr: String): Double {
            val p = Parser(expr)
            val v = p.expr()
            if (p.peek() != null) throw IllegalArgumentException("unexpected input")
            return v
        }

        private class Parser(val s: String) {
            var i = 0
            fun peek(): Char? { while (i < s.length && s[i].isWhitespace()) i++; return if (i < s.length) s[i] else null }
            fun expr(): Double {
                var v = term()
                while (true) when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
            fun term(): Double {
                var v = factor()
                while (true) when (peek()) {
                    '*' -> { i++; v *= factor() }
                    '/' -> { i++; v /= factor() }
                    else -> return v
                }
            }
            fun factor(): Double {
                when (peek()) {
                    '(' -> { i++; val v = expr(); if (peek() == ')') i++ else throw IllegalArgumentException("expected )"); return v }
                    '-' -> { i++; return -factor() }
                    else -> return number()
                }
            }
            fun number(): Double {
                peek(); val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i == start) throw IllegalArgumentException("expected number")
                return s.substring(start, i).toDouble()
            }
        }
    }
}
