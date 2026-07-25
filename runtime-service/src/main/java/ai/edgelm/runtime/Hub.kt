package ai.edgelm.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * EdgeLM Hub v1 — model resolution + integrity, package-manager style (arch doc Part 10).
 *
 * "Download a GGUF and pray" becomes "request a family, get the right, verified build
 * for this device." Hub sits over [ModelCatalog] (the registry) and [ModelStore] (the
 * on-disk cache) and adds three Phase-1 guarantees:
 *
 *   1. **Resolve like a package manager** — ask for a family (`llm.small`) or an id at a
 *      version; get the concrete [ModelSpec] that fits this device's RAM + accelerators.
 *   2. **Verify before you trust** — a downloaded artifact must match its content address
 *      (SHA-256) before it's installed or loaded. Tampered/corrupt files are rejected.
 *   3. **Pin & rollback** — record the installed version; pin a known-good one so a bad
 *      update can be rolled back.
 *
 * Pure Kotlin/JVM (java.security for hashing) — no native code. Cryptographic *signing*
 * (a signed manifest verified against a Hub public key) is the next step on top of this
 * content-addressing; see docs/PHASE1-HUB.md.
 */
object Hub {

    /** Result of verifying a downloaded artifact against its expected content address. */
    sealed interface Verification {
        object Ok : Verification
        /** No expected hash on record — installed but UNVERIFIED (legacy catalog entry). */
        object Unverified : Verification
        data class Mismatch(val expected: String, val actual: String) : Verification
    }

    // ---- Resolve --------------------------------------------------------------

    /**
     * Resolve a request string to the best device-fit model.
     *  - `"<id>"`            → that model (optionally `"<id>@<version>"`).
     *  - `"family:<family>"` → the largest model in that family that fits this device.
     * Returns null if nothing resolves / fits.
     */
    fun resolve(ctx: Context, request: String, ramMb: Int = deviceRamMb(ctx)): ModelSpec? {
        if (request.startsWith("family:")) {
            val family = request.removePrefix("family:")
            return bestInFamily(family, ramMb)
        }
        val (id, version) = request.split("@").let { it[0] to it.getOrNull(1)?.toIntOrNull() }
        val spec = ModelCatalog.byId(id) ?: return null
        if (version != null && spec.version != version) return null
        return spec
    }

    /** Largest model in [family] whose min-RAM fits within ~half the device RAM. */
    fun bestInFamily(family: String, ramMb: Int): ModelSpec? {
        val budget = if (ramMb > 0) (ramMb * 0.5).toInt() else Int.MAX_VALUE
        return ModelCatalog.models
            .filter { familyOf(it) == family && it.minRamMb <= budget }
            .maxByOrNull { it.minRamMb }
    }

    /** The family for [spec]: its explicit [ModelSpec.family], else derived from size so
     *  resolve() works across the existing catalog without tagging every entry.
     *  Tiers: llm.tiny (<1.5 GB RAM) · llm.small (<3 GB) · llm.medium (>=3 GB). */
    fun familyOf(spec: ModelSpec): String = spec.family ?: when {
        spec.minRamMb < 1536 -> "llm.tiny"
        spec.minRamMb < 3072 -> "llm.small"
        else -> "llm.medium"
    }

    // ---- Verify (content-addressed integrity) --------------------------------

    /** Expected SHA-256 for [spec] in [format], or null if the catalog has none. */
    fun expectedHash(spec: ModelSpec, format: String): String? =
        if (format == "litertlm") spec.litertSha256 else spec.sha256

    /** Hash [file] and compare to [spec]'s content address for [format]. */
    fun verify(file: File, spec: ModelSpec, format: String): Verification {
        val expected = expectedHash(spec, format)?.lowercase() ?: return Verification.Unverified
        val actual = sha256Of(file)
        return if (actual.equals(expected, ignoreCase = true)) Verification.Ok
        else Verification.Mismatch(expected, actual)
    }

    /** Streaming SHA-256 of a (possibly multi-GB) file — constant memory. */
    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Pin & rollback -------------------------------------------------------

    private fun pinFile(ctx: Context) = File(ctx.filesDir, "pinned_models")

    /** Record the installed version of [id] (called after a verified install). */
    fun recordInstalledVersion(ctx: Context, id: String, version: Int) {
        val props = readProps(ctx)
        props["$id.installed"] = version.toString()
        writeProps(ctx, props)
    }

    /** Pin [id] to its currently-installed version so a future update can roll back to it. */
    fun pin(ctx: Context, id: String) {
        val props = readProps(ctx)
        props["$id.installed"]?.let { props["$id.pinned"] = it; writeProps(ctx, props) }
    }

    fun unpin(ctx: Context, id: String) {
        val props = readProps(ctx); props.remove("$id.pinned"); writeProps(ctx, props)
    }

    fun pinnedVersion(ctx: Context, id: String): Int? = readProps(ctx)["$id.pinned"]?.toIntOrNull()

    /** True if [id] is pinned and the catalog's version differs → an update would break the pin. */
    fun updateBlockedByPin(ctx: Context, id: String): Boolean {
        val pinned = pinnedVersion(ctx, id) ?: return false
        val catalog = ModelCatalog.byId(id)?.version ?: return false
        return catalog != pinned
    }

    private fun readProps(ctx: Context): MutableMap<String, String> {
        val f = pinFile(ctx)
        if (!f.exists()) return mutableMapOf()
        return f.readLines().mapNotNull { line ->
            line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap(mutableMapOf())
    }

    private fun writeProps(ctx: Context, props: Map<String, String>) {
        pinFile(ctx).writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun deviceRamMb(ctx: Context): Int = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        (mi.totalMem / (1024 * 1024)).toInt()
    }.getOrDefault(0)
}
