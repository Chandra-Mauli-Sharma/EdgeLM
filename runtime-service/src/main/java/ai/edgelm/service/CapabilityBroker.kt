package ai.edgelm.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability-based permission broker for the shared runtime (arch doc Part 7).
 *
 * The coarse gate — `ai.edgelm.permission.USE_RUNTIME`, enforced by the OS at bind
 * time (see AndroidManifest) — only decides *whether an app may talk to the runtime
 * at all*. This broker adds the fine-grained layer on top: **which AI capabilities**
 * a given calling app is actually allowed to use, mapped from the kernel-verified
 * Binder UID, granted through EdgeLM's own consent model, and revocable per app.
 *
 * Two things must both hold for a capability to be allowed:
 *   1. **Declared intent** — the app lists the matching `<uses-permission>` in its
 *      manifest (e.g. `ai.edgelm.CHAT`). This is auditable on the Play listing and
 *      is how an app states, up front and visibly, what it intends to use.
 *   2. **Granted** — EdgeLM's grant store says GRANTED for that (package, capability).
 *      Low-risk capabilities are *grant-on-first-use* (auto-granted the first time,
 *      recorded, and revocable). High-risk ones return NEEDS_CONSENT until the user
 *      explicitly approves them via [PermissionConsentActivity].
 *
 * Identity is never trusted from the app: the UID is stamped by Binder, and we map
 * UID → package(s) → grants ourselves. An app cannot claim another's capabilities.
 *
 * Pure Kotlin, no native code. State lives in the :core process (the service), keyed
 * by package name (stable) rather than UID (which the OS can recycle).
 */
class CapabilityBroker(private val appContext: Context) {

    companion object {
        private const val TAG = "CapabilityBroker"
        private const val PREFS = "edgelm_grants"

        /** Pseudo-identity for the loopback OpenAI HTTP shim, which carries no UID. */
        const val HTTP_PSEUDO_PACKAGE = "ai.edgelm.http"

        /** SharedPreferences key for a (package, capability) grant. */
        private fun key(pkg: String, cap: Capability) = "grant:$pkg:${cap.id}"
    }

    /** Risk tier decides the default grant behaviour when no explicit grant exists. */
    enum class Risk {
        /** Auto-granted on first use, recorded, and revocable (read-only, own-data). */
        LOW,
        /** Requires explicit, revocable user consent before first use. */
        HIGH,
    }

    /**
     * A first-class AI capability. [permission] is the manifest string an app must
     * declare to signal intent; [risk] drives the default grant policy.
     */
    enum class Capability(val id: String, val permission: String, val risk: Risk) {
        CHAT("chat", "ai.edgelm.CHAT", Risk.LOW),
        EMBED("embed", "ai.edgelm.EMBED", Risk.LOW),
        VISION("vision", "ai.edgelm.VISION", Risk.LOW),
        AUDIO("audio", "ai.edgelm.AUDIO", Risk.LOW),
        /** Run inference while NOT the foreground app (battery-abuse vector → consent). */
        BACKGROUND_INFERENCE("background_inference", "ai.edgelm.BACKGROUND_INFERENCE", Risk.HIGH);

        companion object {
            fun byId(id: String?): Capability? = entries.firstOrNull { it.id == id }
        }
    }

    /** Outcome of a capability check. */
    sealed interface Decision {
        object Allow : Decision
        /** Denied outright; [reason] is safe to surface to the calling app. */
        data class Deny(val reason: String) : Decision
        /** The app must obtain explicit user consent first (launch the consent UI). */
        data class NeedsConsent(val capability: Capability) : Decision
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Small per-package rate limiter so a single app can't monopolise the shared engine.
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()

    // ---- Public API -----------------------------------------------------------

    /**
     * Decide whether the app behind [uid] may use [cap]. Also enforces the per-app
     * request quota. Call on the Binder thread (needs the real calling UID).
     */
    fun check(uid: Int, cap: Capability): Decision {
        // The runtime's own process and the platform are always allowed.
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return Decision.Allow

        val pkgs = packagesFor(uid)
            ?: return Decision.Deny("unrecognised caller (uid $uid)")

        // Intent must be declared in at least one of the UID's packages.
        val declaring = pkgs.firstOrNull { declaresPermission(it, cap.permission) }
            ?: return Decision.Deny(
                "app does not declare <uses-permission android:name=\"${cap.permission}\"/>"
            )

        // Grant state, then rate quota.
        return when (grantState(declaring, cap)) {
            GrantState.GRANTED -> {
                if (rateLimiterFor(declaring).tryAcquire()) Decision.Allow
                else Decision.Deny("rate limit exceeded — too many requests; slow down")
            }
            GrantState.DENIED -> Decision.Deny("capability '${cap.id}' was denied for this app")
            GrantState.UNSET -> when (cap.risk) {
                // Grant-on-first-use: record it so it's visible and revocable, then allow.
                Risk.LOW -> {
                    setGrant(declaring, cap, true)
                    Log.i(TAG, "grant-on-first-use: $declaring -> ${cap.id}")
                    if (rateLimiterFor(declaring).tryAcquire()) Decision.Allow
                    else Decision.Deny("rate limit exceeded — too many requests; slow down")
                }
                Risk.HIGH -> Decision.NeedsConsent(cap)
            }
        }
    }

    /** Non-mutating query for the SDK's `permissions().has(cap)`; true iff currently granted. */
    fun isGranted(uid: Int, cap: Capability): Boolean {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return true
        val pkgs = packagesFor(uid) ?: return false
        return pkgs.any { declaresPermission(it, cap.permission) && grantState(it, cap) == GrantState.GRANTED }
    }

    /** The loopback HTTP shim's decision. Debug-only path; treated as one pseudo-app. */
    fun checkHttp(cap: Capability): Decision {
        return when (grantState(HTTP_PSEUDO_PACKAGE, cap)) {
            GrantState.DENIED -> Decision.Deny("capability '${cap.id}' denied for the local HTTP client")
            else -> {
                if (grantState(HTTP_PSEUDO_PACKAGE, cap) == GrantState.UNSET)
                    setGrant(HTTP_PSEUDO_PACKAGE, cap, true)
                if (rateLimiterFor(HTTP_PSEUDO_PACKAGE).tryAcquire()) Decision.Allow
                else Decision.Deny("rate limit exceeded on the local HTTP client")
            }
        }
    }

    // ---- Grant store (persistent, package-keyed) ------------------------------

    enum class GrantState { GRANTED, DENIED, UNSET }

    fun grantState(pkg: String, cap: Capability): GrantState =
        when (prefs.getString(key(pkg, cap), null)) {
            "granted" -> GrantState.GRANTED
            "denied" -> GrantState.DENIED
            else -> GrantState.UNSET
        }

    /** Record an explicit grant/deny. Used by [PermissionConsentActivity] and the settings UI. */
    fun setGrant(pkg: String, cap: Capability, granted: Boolean) {
        prefs.edit().putString(key(pkg, cap), if (granted) "granted" else "denied").apply()
    }

    /** Clear a grant back to UNSET (re-prompts / re-grants on next use). */
    fun revoke(pkg: String, cap: Capability) {
        prefs.edit().remove(key(pkg, cap)).apply()
    }

    // ---- Egress policy (data-flow firewall, remembered per destination) -------
    //
    // Per-call `allow_egress` / `allow_tainted_egress` flags are one-shot consent. A real
    // firewall remembers a decision per destination host so an app needn't re-consent every
    // call, and so a destination can be *permanently blocked*. Two independent axes per host:
    //   egress:<host>        — may data reach this host at all?
    //   egress-taint:<host>  — may LOCAL (tainted) data reach this host?
    // ALLOW short-circuits the flag; DENY overrides it (hard block); UNSET falls back to the
    // per-call flag. Keyed by host (the destination), the meaningful axis for egress.

    enum class EgressState { ALLOW, DENY, UNSET }

    private fun readEgress(v: String?): EgressState = when (v) {
        "allow" -> EgressState.ALLOW; "deny" -> EgressState.DENY; else -> EgressState.UNSET
    }
    /** Normalize a URL or host to a bare lowercase host (no scheme/port/path). */
    fun hostOf(urlOrHost: String): String = runCatching {
        val h = if (urlOrHost.contains("://")) java.net.URI(urlOrHost).host else urlOrHost.substringBefore('/')
        (h ?: urlOrHost).substringBefore(':').trim().lowercase()
    }.getOrDefault(urlOrHost.trim().lowercase())

    fun egressPolicy(host: String): EgressState = readEgress(prefs.getString("egress:${hostOf(host)}", null))
    fun egressTaintPolicy(host: String): EgressState = readEgress(prefs.getString("egress-taint:${hostOf(host)}", null))

    /** Set the reachability policy for [host]; null clears it back to UNSET. */
    fun setEgressPolicy(host: String, state: EgressState?) {
        val k = "egress:${hostOf(host)}"
        if (state == null || state == EgressState.UNSET) prefs.edit().remove(k).apply()
        else prefs.edit().putString(k, if (state == EgressState.ALLOW) "allow" else "deny").apply()
    }
    /** Set the tainted-data policy for [host]; null clears it. */
    fun setEgressTaintPolicy(host: String, state: EgressState?) {
        val k = "egress-taint:${hostOf(host)}"
        if (state == null || state == EgressState.UNSET) prefs.edit().remove(k).apply()
        else prefs.edit().putString(k, if (state == EgressState.ALLOW) "allow" else "deny").apply()
    }
    /** Clear both policies for [host]. */
    fun forgetEgress(host: String) {
        val h = hostOf(host)
        prefs.edit().remove("egress:$h").remove("egress-taint:$h").apply()
    }
    /** All remembered egress policies: host -> (reachability, tainted). Powers `edgelm egress list`. */
    fun allEgressPolicies(): Map<String, Pair<EgressState, EgressState>> {
        val hosts = prefs.all.keys
            .filter { it.startsWith("egress:") || it.startsWith("egress-taint:") }
            .map { it.substringAfter(':') }.toSet()
        return hosts.associateWith { egressPolicy(it) to egressTaintPolicy(it) }
    }

    /** Every recorded (package, capability, granted) triple — powers the system "AI permissions" UI. */
    fun allGrants(): List<Triple<String, Capability, Boolean>> =
        prefs.all.mapNotNull { (k, v) ->
            val parts = k.removePrefix("grant:").split(":")
            if (!k.startsWith("grant:") || parts.size < 2) return@mapNotNull null
            val cap = Capability.byId(parts.last()) ?: return@mapNotNull null
            val pkg = parts.dropLast(1).joinToString(":")
            Triple(pkg, cap, v == "granted")
        }

    // ---- Identity + manifest resolution ---------------------------------------

    /** Packages sharing [uid] (usually one; more under a sharedUserId). */
    private fun packagesFor(uid: Int): List<String>? =
        appContext.packageManager.getPackagesForUid(uid)?.toList()?.takeIf { it.isNotEmpty() }

    /** True iff [pkg] declares [permission] via <uses-permission> in its manifest. */
    private fun declaresPermission(pkg: String, permission: String): Boolean = runCatching {
        val info = appContext.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

    /** Friendly app label for a package (for the consent UI / logs). */
    fun labelFor(pkg: String): String = runCatching {
        appContext.packageManager.getApplicationLabel(
            appContext.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    private fun rateLimiterFor(pkg: String): RateLimiter =
        rateLimiters.getOrPut(pkg) { RateLimiter(maxTokens = 30, refillPerSec = 10.0) }

    /**
     * Token-bucket rate limiter: a burst of [maxTokens] requests, refilling at
     * [refillPerSec]. Cheap admission-side quota so one misbehaving app is throttled
     * to its own bucket rather than starving the shared engine (arch doc Part 7:
     * "a malicious app hits its quota and is throttled, not the device").
     */
    private class RateLimiter(private val maxTokens: Int, private val refillPerSec: Double) {
        private var tokens = maxTokens.toDouble()
        private var lastNanos = System.nanoTime()
        @Synchronized fun tryAcquire(): Boolean {
            val now = System.nanoTime()
            tokens = minOf(maxTokens.toDouble(), tokens + (now - lastNanos) / 1e9 * refillPerSec)
            lastNanos = now
            return if (tokens >= 1.0) { tokens -= 1.0; true } else false
        }
    }
}
