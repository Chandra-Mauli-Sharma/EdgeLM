# Phase 1 — On-device Verification Recipe

What the unit tests can't cover: the cross-process broker gate and the consent UI.
These are concrete `adb` steps against a **debug** build. Automated coverage lives in
`HubTest` + `AISchedulerTest` (`./gradlew :runtime-service:testDebugUnitTest`).

Prereqs: debug build installed and open, device connected, a demo app that binds the
runtime (e.g. `demo-app-a`).

## 1. Grant-on-first-use (positive path)

Make one inference from a demo app, then inspect the grant store (the `:core` service
shares the app's data dir; `run-as` works on debug builds):

```bash
adb shell run-as ai.edgelm.runtime cat shared_prefs/edgelm_grants.xml
```

Expect a row like `<string name="grant:ai.edgelm.demo.a:chat">granted</string>` — CHAT
was auto-granted and recorded (revocable), no prompt. This proves declaration + GOFU.

## 2. Broker deny path

Flip that grant to `denied`, restart the service so `:core` re-reads its prefs, then
make the demo app infer again:

```bash
adb shell run-as ai.edgelm.runtime sh -c \
  "sed -i 's#\(grant:[^\"]*:chat\">\)granted#\1denied#' shared_prefs/edgelm_grants.xml"
adb shell am force-stop ai.edgelm.runtime      # :core reloads prefs on next bind
```

Reopen the app, infer from the demo app → expect the stream to fail with
`EdgeLM: capability 'chat' was denied for this app`. Delete the row (or set it back to
`granted`) to restore. This proves the gate actually blocks a denied caller.

> Deny-by-non-declaration is also covered structurally: an app that never lists
> `<uses-permission android:name="ai.edgelm.CHAT"/>` is rejected with "app does not
> declare…". The SDK merges CHAT, so to see this you'd need an app that binds without
> the SDK (or strips CHAT with `tools:node="remove"`).

## 3. Consent UI (high-risk capability)

Launch the consent surface directly for `background_inference` (no app change needed):

```bash
adb shell am start -n ai.edgelm.runtime/ai.edgelm.runtime.PermissionConsentActivity \
  --es ai.edgelm.extra.CAPABILITY background_inference \
  --es ai.edgelm.extra.PACKAGE ai.edgelm.demo.a
```

Expect the consent screen naming the app + capability. Tap **Allow** → verify a
`grant:ai.edgelm.demo.a:background_inference=granted` row appears (step 1's `cat`). Tap
**Don't allow** on a repeat → it records `denied`. This proves consent + persistence.

End-to-end (optional, needs a 1-line demo tweak): have the demo app call
`EdgeLM.chat(..., priority = EdgeLM.BACKGROUND)`. Before consent → denied with
"consent required for 'background_inference'"; after Allow → it runs.

## 4. Hub integrity (tamper rejection)

Automated in `HubTest` (Ok / Mismatch / Unverified). On-device end-to-end:

1. Put a deliberately wrong `sha256 = "0".repeat(64)` on a small catalog entry; rebuild.
2. `.\tools\edgelm.ps1 pull <that-id>` (or the app).
3. Expect the download to **fail** with "integrity check failed … does not match expected
   SHA-256", and no file installed (`adb shell run-as ai.edgelm.runtime ls files/models`).
4. Restore the correct/absent hash → it installs again.

## 5. Scheduler governor (thermal defer)

```bash
adb shell cmd thermalservice override-status 3     # emulate SEVERE thermal
```
Then a BATCH/BACKGROUND request (the HTTP shim submits at BATCH) should be deferred with
`deferred: device is too hot for background inference`, while a foreground Binder request
still runs. Clear with `adb shell cmd thermalservice reset`.

## Automated (run anytime)

```bash
./gradlew :runtime-service:testDebugUnitTest    # HubTest + AISchedulerTest + EngineRoutingTest
```
