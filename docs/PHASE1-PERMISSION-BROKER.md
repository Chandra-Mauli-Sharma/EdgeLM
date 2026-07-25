# Phase 1 — Capability Permission Broker

Fine-grained, per-app AI permissions on top of the coarse bind gate. Implements
arch-doc **Part 7** (Security & Permission Model). This is the Phase 1 gate blocker:
you cannot safely admit non-partner apps to a shared runtime without it.

## Two layers of gating

| Layer | Enforced by | Decides |
|-------|-------------|---------|
| `ai.edgelm.permission.USE_RUNTIME` | **OS**, at `bindService` (manifest `android:permission`) | May this app talk to the runtime *at all*? |
| `CapabilityBroker` | **EdgeLM**, per request | Which *capabilities* (chat / embed / vision / background) may it use? |

The OS gate is binary and install-time. The broker adds identity-bound, revocable,
consent-aware capability control that the OS permission model alone can't express.

## Capabilities

| id | manifest permission | risk | default policy |
|----|--------------------|------|----------------|
| `chat` | `ai.edgelm.CHAT` | LOW | grant-on-first-use |
| `embed` | `ai.edgelm.EMBED` | LOW | grant-on-first-use |
| `vision` | `ai.edgelm.VISION` | LOW | grant-on-first-use |
| `background_inference` | `ai.edgelm.BACKGROUND_INFERENCE` | HIGH | explicit consent |

## The rule

A capability is **allowed** iff **both** hold:

1. **Declared intent** — the calling app lists `<uses-permission android:name="ai.edgelm.<CAP>"/>`
   (auditable on its Play listing). The SDK declares `ai.edgelm.CHAT` for every
   consumer via manifest merge, so the common case needs zero app work.
2. **Granted** — the broker's grant store says GRANTED. Low-risk capabilities are
   auto-granted the first time (recorded + revocable). High-risk return
   `NeedsConsent` until the user approves via `PermissionConsentActivity`.

Identity is the **kernel-verified Binder UID** → package(s) → grants. An app can
only ever ask about / act as itself; it cannot spoof another's capabilities.

## Request path

- `IEdgeLMService.submit(...)` reads `Binder.getCallingUid()` on the Binder thread and
  calls `broker.check(uid, CHAT)`. Non-foreground priorities (BATCH/BACKGROUND)
  additionally require `BACKGROUND_INFERENCE`. Denials come back through
  `ITokenCallback.onError("EdgeLM: <reason>")` — no generation runs.
- The loopback **HTTP shim** (debug-only) routes through `broker.checkHttp(CHAT)` under
  a single `ai.edgelm.http` pseudo-identity; a denial throws before generation.
- Per-app **token-bucket rate limit** (burst 30, refill 10/s) throttles a misbehaving
  app to its own bucket instead of starving the shared engine.

## SDK surface

```kotlin
EdgeLM.permissions().has(EdgeLM.CAP_CHAT)                       // suspend -> Boolean
EdgeLM.permissions().needsConsent(EdgeLM.CAP_BACKGROUND_INFERENCE)
EdgeLM.permissions().request(context, EdgeLM.CAP_BACKGROUND_INFERENCE)  // launches consent UI
```

AIDL additions are **append-only** (`hasCapability`, `capabilityNeedsConsent`) — ABI stable.

## Files

- `CapabilityBroker.kt` — capabilities, grant store, check/consent logic, rate limiter.
- `PermissionConsentActivity.kt` — the consent surface for high-risk capabilities.
- `EdgeLMService.kt` — gates `submit()` + the HTTP path; implements the AIDL queries.
- `sdk/.../EdgeLM.kt` — `permissions()` facade + capability constants.
- manifests — capability `<permission>` defs (runtime) + `CHAT` merge (SDK).

## Not yet done / hardening

- **Consent referrer check**: the consent activity prefers the launch `referrer` over
  the passed package extra, but a fuller identity check (signature match) is worth
  adding before treating consent as security-critical.
- **Energy budget**: the rate limiter is request-count based. Arch-doc Part 7's
  millijoule/hour budget wants energy accounting fed from the metrics layer — hook is
  the same admission point.
- **System "AI permissions" settings screen**: `broker.allGrants()` exposes the data;
  the settings UI (revoke anything) is a small follow-up.
- **On-device verification**: the sandbox can't build the NDK/Gradle app — compile +
  smoke-test the deny path (an app without `ai.edgelm.CHAT`) on device.
