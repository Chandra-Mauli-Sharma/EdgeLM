package ai.edgelm.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device vector index for RAG (arch doc Part 12 — "Embeddings + RAG ... on-device
 * vector index as a system plugin"). Stores document embeddings in per-app namespaced
 * collections and searches them by semantic similarity, entirely locally.
 *
 * Isolation: every collection lives under a namespace (the caller's app identity), so one
 * app can never read or search another's vectors — the same block-level isolation the
 * runtime enforces for KV.
 *
 * Search is brute-force cosine similarity. Because [EdgeLM] embeddings are L2-normalized
 * (see llama_runner::embed), cosine == dot product, so a query is one pass of dot products
 * — plenty fast for the thousands-of-docs scale an app keeps on a phone. (A real ANN index
 * is a later optimization; correctness first.)
 *
 * Persistence: one JSONL file per (namespace, collection) under [baseDir]. Loaded lazily
 * into memory and rewritten on mutation. Pure JVM — no Android framework, no native code.
 */
class VectorStore(private val baseDir: File) {

    data class Hit(val id: String, val score: Float, val text: String, val meta: String)

    private class Record(val id: String, val text: String, val meta: String, val vec: FloatArray)

    // key = "<namespace>/<collection>" -> records
    private val cache = ConcurrentHashMap<String, MutableList<Record>>()

    private fun key(ns: String, col: String) = "$ns/$col"
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun file(ns: String, col: String) =
        File(File(baseDir, safe(ns)).apply { mkdirs() }, safe(col) + ".jsonl")

    @Synchronized
    private fun load(ns: String, col: String): MutableList<Record> =
        cache.getOrPut(key(ns, col)) {
            val f = file(ns, col)
            val list = ArrayList<Record>()
            if (f.exists()) f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val o = JSONObject(line)
                    val va = o.getJSONArray("vec")
                    val vec = FloatArray(va.length()) { va.getDouble(it).toFloat() }
                    list.add(Record(o.getString("id"), o.optString("text"), o.optString("meta"), vec))
                }
            }
            list
        }

    @Synchronized
    private fun persist(ns: String, col: String, list: List<Record>) {
        val sb = StringBuilder()
        for (r in list) {
            val va = JSONArray(); r.vec.forEach { va.put(it.toDouble()) }
            sb.append(JSONObject()
                .put("id", r.id).put("text", r.text).put("meta", r.meta).put("vec", va)
                .toString()).append('\n')
        }
        file(ns, col).writeText(sb.toString())
    }

    /** Add or replace a document (by id) with its embedding. */
    @Synchronized
    fun upsert(ns: String, col: String, id: String, text: String, meta: String, vec: FloatArray) {
        val list = load(ns, col)
        list.removeAll { it.id == id }
        list.add(Record(id, text, meta, vec))
        persist(ns, col, list)
    }

    /** Top-[topK] documents by cosine similarity to [queryVec] (dot product; normalized). */
    fun query(ns: String, col: String, queryVec: FloatArray, topK: Int): List<Hit> {
        val list = load(ns, col)
        return list.asSequence()
            .map { Hit(it.id, dot(queryVec, it.vec), it.text, it.meta) }
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))
            .toList()
    }

    @Synchronized
    fun delete(ns: String, col: String, ids: Set<String>) {
        val list = load(ns, col)
        if (list.removeAll { it.id in ids }) persist(ns, col, list)
    }

    /** (collection name, document count) for a namespace. */
    fun collections(ns: String): List<Pair<String, Int>> {
        val dir = File(baseDir, safe(ns))
        return dir.listFiles { f -> f.extension == "jsonl" }?.map { f ->
            val name = f.nameWithoutExtension
            name to load(ns, name).size
        } ?: emptyList()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
