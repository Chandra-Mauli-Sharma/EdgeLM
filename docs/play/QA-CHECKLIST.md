# EdgeLM — Release QA Checklist

Run this on a **real device** before publishing. Ideally test on two: one mid-range
(4 GB RAM) and one flagship (8 GB+). Check the box only when the expected result is
seen. Anything that fails is a blocker unless noted "(minor)".

Build under test: **release** unless a step says debug.
Produce it with `.\gradlew.bat :runtime-service:bundleRelease` (signed `.aab`) or
`assembleRelease` (signed APK for sideloading). Install the release APK with
`adb install -r runtime-service\build\outputs\apk\release\runtime-service-release.apk`.

---

## 1. Release-build integrity (most important — R8 can silently break these)

- [ ] App installs and launches without crashing.
- [ ] Open **Playground**, download a model, send "hello" → tokens stream back.
      *(Proves the JNI bridge survived R8 obfuscation. A crash/`UnsatisfiedLinkError`
      here means the keep-rules in `proguard-rules.pro` need a look.)*
- [ ] tokens/sec line appears after the reply (perf path intact).
- [ ] No open localhost port in release:
      `adb shell run-as ai.edgelm.runtime cat /proc/net/tcp | findstr 0588`
      should return nothing (`0588` = port 1408). The HTTP shim must be **off** in
      release.
- [ ] Logcat shows `HTTP shim disabled in release build` (not "HTTP shim on …").

## 2. First run / onboarding

- [ ] Fresh install (or `adb shell pm clear ai.edgelm.runtime`) → **Onboarding**
      screen shows on first launch (ghost, Private / Offline / Shared, Get started).
- [ ] Tap **Get started** → lands on the main screen.
- [ ] Fully close and relaunch → onboarding does **not** show again.

## 3. Splash & branding

- [ ] Splash shows the matcha-gradient ghost with **no clipped edges** (top-left nick
      gone) and the **EdgeLM** wordmark below it.
- [ ] Splash is visible for a clear beat, then animates out smoothly (no glitchy
      instant flash).
- [ ] Launcher icon in the app drawer is the Goo Drip ghost.

## 4. Model management (Simple mode — default)

- [ ] Main screen shows **"Choose your AI"** with one **★ Recommended** card sized to
      the device (bigger models on the flagship, smaller on the 4 GB device).
- [ ] Card wording is jargon-free (friendly name, one-word speed hint, size, plain
      description) — no params/quant/context/license/RAM figures.
- [ ] **Download** → progress shows on the card **and** in a notification with a
      Cancel action; status ends "Downloaded & active ✓".
- [ ] After download, status reads **"✓ Ready to use"** and the card shows **In use ✓**.
- [ ] **Show more options** reveals the rest of the catalog.
- [ ] Download a second model → **Use this one** switches instantly (no re-download).
- [ ] **Remove** a non-active model → frees it; **Remove** the active one → status
      returns to "Pick an AI…".
- [ ] Leave the app **mid-download** → download continues (foreground service); reopen
      → the card reconciles and finishes.
- [ ] Cancel a download from the notification → stops; partial file cleaned up.

## 5. Advanced mode

- [ ] Tap **Advanced ›** → full catalog with params, context, license, RAM needs,
      "Best for" lines, and device RAM readout; choice is remembered after relaunch.
- [ ] On the 4 GB device, heavy models show the **"⚠ may exceed this device's RAM"**
      warning.

## 6. Playground

- [ ] Example-prompt chips send on tap and stream a reply.
- [ ] **Stop** mid-generation halts near-instantly; status shows "■ Stopped · N tokens".
- [ ] **Clear** wipes the chat and starts a fresh conversation (ask a follow-up after
      clear → no carried-over context).
- [ ] Multi-turn memory works before clearing (reference an earlier message).
- [ ] With no model installed, sending shows the "no model installed" message.

## 7. Notification & lifecycle

- [ ] Ongoing **EdgeLM Runtime** notification appears, subtitle **"On-device AI"**
      (no `127.0.0.1` port).
- [ ] During generation it reads "Generating for …"; idle it reads "Ready · <model>".
- [ ] **Free memory** action unloads the model (RAM drops via
      `adb shell dumpsys meminfo ai.edgelm.runtime:core`); next request lazily reloads.
- [ ] Notification **persists after swiping the app away**; **Stop** action removes it
      and stops the runtime.
- [ ] Idle ~5 min with no requests → model auto-unloads ("… unloaded to save memory");
      a new request reloads it. *(minor — long wait; spot-check if time allows.)*

## 8. Security gate (cross-app)

- [ ] Install a debug demo app (demo-app-a/b) **after** the runtime → it binds and runs
      inference (has `USE_RUNTIME` via the SDK manifest merge).
- [ ] A throwaway app that binds **without** the SDK/permission gets a `SecurityException`
      on `bindService` (gate works). *(optional but recommended)*
- [ ] Second app using the runtime adds little memory (shared model) — compare
      `dumpsys meminfo` before/after.

## 9. Edge cases

- [ ] Airplane mode with a model already installed → Playground still works (offline).
- [ ] Airplane mode with **no** model → Download fails gracefully with an error, no crash.
- [ ] Rotate the screen on each screen → no crash, state preserved.
- [ ] Force-stop then relaunch → recovers cleanly.
- [ ] Low-RAM device: recommended model loads and runs; a too-big model shows the RAM
      warning and (if downloaded) fails to load without crashing the app.

## 10. Pre-submit (Play Console)

- [ ] `versionCode` bumped from the last upload (currently `1`).
- [ ] Privacy policy URL is live and reachable.
- [ ] Data Safety = "No data collected / No data shared".
- [ ] `specialUse` FGS justification pasted (see `DATA-SAFETY.md`).
- [ ] Feature graphic (1024×500) + 512 icon + 2–8 screenshots uploaded.
- [ ] Uploaded to **Internal testing** first; **pre-launch report** reviewed and clean.

---

### Quick reset commands

```powershell
adb shell pm clear ai.edgelm.runtime          # wipe state (models, onboarding flag)
adb shell run-as ai.edgelm.runtime rm files/models/*.gguf   # remove models only
adb shell dumpsys meminfo ai.edgelm.runtime:core            # runtime memory
adb logcat -s EdgeLMService EdgeLMDownload                  # runtime logs
```
