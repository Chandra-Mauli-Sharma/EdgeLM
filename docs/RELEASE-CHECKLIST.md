# Release checklist — EdgeLM runtime (Phase D)

_Shipping the build that includes the LiteRT-LM GPU engine (live, gated to Adreno devices).
Run these on Windows; the build/size steps can't be validated in the agent sandbox._

## 1. Pre-build

- [ ] `versionCode` in `runtime-service/build.gradle.kts` is **(last value uploaded to Play) + 1**.
      Currently set to **15** — lower it to 14 if 14 was never actually uploaded, or raise it.
- [ ] `versionName` bumped (currently `0.1.15`).
- [ ] `keystore.properties` + `*.jks` present at repo root (gitignored) so the release is **signed**.
      If absent, the release builds **unsigned** and Play will reject it.

## 2. Build & verify

```
.\gradlew --stop
Remove-Item -Recurse -Force runtime-service\.cxx          # clean native (bakes in KleidiAI-off)
.\gradlew :runtime-service:assembleDebug                  # sanity: compiles with LiteRT live
.\gradlew :runtime-service:testDebugUnitTest              # routing / allowlist / capability tests
.\gradlew :runtime-service:bundleRelease                  # the .aab you upload
```

- [ ] `assembleDebug` succeeds (LiteRT active in debug).
- [ ] Unit tests pass.
- [ ] `bundleRelease` succeeds and is **signed** (`...outputs/bundle/release/*.aab`).

## 3. Size check — THE gate for this release

Adding the LiteRT-LM AAR pulls in native inference libs, so the arm64 download grows. You've hit
Play's APK-size / dropped-device warnings before, so verify **before** committing to this shape:

- [ ] In Play Console (or `bundletool build-apks --mode=default` + inspect), check the **arm64
      download + install size**. App Bundle already delivers LiteRT's arm64 libs only to arm64
      devices, and nothing to `armeabi-v7a`, so 32-bit users are unaffected.
- [ ] If the arm64 size trips Play's limits or re-triggers the device-support warning:
      **fall back to a dynamic feature module** for LiteRT (base APK stays small; LiteRT delivered
      on-demand only to Adreno devices). This is the pre-built escape hatch — see
      docs/ENGINE-ABSTRACTION.md. Otherwise, ship as-is.

## 4. Upload & roll out

- [ ] Upload the `.aab` to the Play Console.
- [ ] Confirm **no new dropped-device warnings** (compare to the last CPU-only release).
- [ ] Fill in the "What's new" notes (draft below).
- [ ] Start a **staged rollout** (e.g. 10–20%) rather than 100% — LiteRT is unmeasured (see §6),
      so watch crash-free rate / ANRs on GPU (Adreno) devices for a few days before widening.

## 5. "What's new" (draft)

> - Faster on-device AI, with GPU acceleration on supported devices.
> - More reliable model downloads — large downloads now resume automatically if interrupted.
> - New model options and a smoother first-run setup.
> - Stability and performance improvements.

(Keep it user-facing; don't name engines/SoCs.)

## 6. Known caveats going into this release

- **LiteRT-GPU is unmeasured.** No device has yet shown LiteRT beating llama.cpp; the SoC
  allowlist assumes Adreno works from Google's published numbers. The staged rollout + crash
  monitoring is the safety net. If Adreno devices show regressions or crashes, the allowlist can
  be tightened (or LiteRT disabled) in a fast follow-up.
- **llama.cpp is the floor everywhere.** Non-Adreno devices never see LiteRT (allowlist + picker
  filter), and any LiteRT load failure self-corrects to llama.cpp via the cached verdict.
- **Model gating.** Only ship **ungated** `.litertlm` models (the catalog uses Qwen2.5-1.5B,
  Apache-2.0) — gated models (e.g. Gemma) 401 on auto-download.

## 7. Post-release

- [ ] Get the real **Adreno GPU tok/s measurement** (a Pixel/Snapdragon device) to confirm the
      LiteRT path is actually a win — the go/no-go that was deferred. If it isn't, plan to disable
      the LiteRT tier in the next release.
