# EdgeLM — Launch Week Reel Campaign (7 days)

App is **live on Google Play**. This week's goal: convert "it exists" into installs,
integrations, and followers — while seeding **what's coming** (GPU acceleration, the
SDK, open-source, the Hub).

**Cadence:** one reel/day, 7 days. **Format:** vertical 9:16, 12–20s, all-graphics,
brand ghost ("Goo Drip"), obsidian bg + lime accent, punchy on-screen text, upbeat
royalty-free track. **Post time:** 6–9pm local (best reach). Cross-post each to
Instagram Reels, YouTube Shorts, TikTok; pull a still + hook for X.

**Audience rotation:** Mon users · Tue devs · Wed enthusiasts · Thu users · Fri
teaser(all) · Sat devs/enthusiasts · Sun vision+recap(all).

Rendered files live in `docs/marketing/reels/` (`day1_*.mp4` … `day7_*.mp4`).

---

## Day 1 — LAUNCH · general users
**Hook (0–2s):** "Your phone just got an AI that never phones home."
**Beats:**
1. Black screen, ghost blinks in. Text: *"AI on your phone."*
2. *"No cloud. No account. No tracking."* (each line snaps in)
3. Phone outline; a lock clicks. *"Your prompts never leave the device."*
4. CTA: *"EdgeLM — free on Google Play."* + Play badge.
**On-screen text:** short, 3–5 words/line. **Audio:** confident, minimal beat.
**Caption:** Your phone is a supercomputer. Now it runs AI *locally* — private, offline, free. Meet EdgeLM. 👻 Link in bio.
**Tags:** #onDeviceAI #privacy #Android #localAI #offlineAI #AItools #privateAI

## Day 2 — DEVELOPERS · the SDK
**Hook:** "Add on-device AI to your Android app in 3 lines."
**Beats:**
1. Code editor vibe. Type: `implementation("...EdgeLM:sdk:0.1.0")`.
2. `EdgeLM.chat("...").collect { token -> }` — tokens stream on screen.
3. *"No model files. No cloud keys. No manifest changes."*
4. CTA: *"JitPack + a 5-min sample. github.com/…/EdgeLM"*.
**On-screen text:** real code, monospace, lime highlights. **Audio:** clicky, techy.
**Caption:** Ship on-device AI without shipping a model. One dependency, a cold Flow of tokens, done. Sample + docs in the repo. #AndroidDev #Kotlin #JitPack #AI #SDK #mobiledev #onDeviceAI

## Day 3 — ENTHUSIASTS · the shared-runtime idea
**Hook:** "What if every app shared ONE AI model — instead of bundling its own?"
**Beats:**
1. Five app icons, each dragging a heavy "480 MB" weight. *"Today: every app ships its own engine."*
2. They snap to a single shared core. *"EdgeLM: one model, mmap'd once."*
3. Counter animates: *"App #2 added: +3.5 MB."* (not +480).
4. *"Inference belongs in the OS — like the camera or GPS."*
**Audio:** builds to a satisfying "click." **Caption:** The trick isn't a smaller model — it's *sharing* it. One copy in RAM, every app calls it. That's EdgeLM. #AI #systems #Android #edgecomputing #LLM #engineering

## Day 4 — GENERAL USERS · offline proof
**Hook:** "Airplane mode. No signal. Still answers."
**Beats:**
1. Airplane-mode icon flips on; wifi/■ bars go dark.
2. A prompt types itself; answer streams anyway.
3. *"Plane. Tunnel. Dead zone. Doesn't matter."*
4. CTA: Play badge + *"Works 100% offline."*
**Audio:** calm→triumphant. **Caption:** Real AI with zero bars. Once a model's downloaded, EdgeLM runs with no internet at all. ✈️ Free on Google Play. #offlineAI #privacy #Android #nointernet #localLLM

## Day 5 — TEASER · what's coming (all audiences)
**Hook:** "It's fast now. Next week it gets a GPU."
**Beats:**
1. CPU meter at ~23 tok/s. *"On-device, today."*
2. A "VULKAN" bolt strikes; meter surges past 30+. *"GPU acceleration — incoming."*
3. Quick roadmap ticker: *"GPU ⚡ · open-source 📖 · the Hub 🧩"*.
4. CTA: *"Follow — you'll want to see the numbers."*
**Audio:** rising synth, anticipatory. **Caption:** on-device AI is only getting faster. GPU offload is landing, the runtime's going open-source, and the model Hub is next. Buckle up. 👻⚡ #AI #GPU #Vulkan #comingsoon #Android #buildinpublic

## Day 6 — DEVELOPERS/ENTHUSIASTS · developer experience
**Hook:** "Five minutes to your first token."
**Beats:**
1. Timer starts. Step 1: add repo. Step 2: add dependency. Step 3: `EdgeLM.chat()`.
2. Sample app opens, streams a reply. Timer stops at ~5:00.
3. *"Typed API · streaming · no cloud · shared runtime."*
4. CTA: *"hello-edgelm sample in the repo."*
**Audio:** brisk, montage-y. **Caption:** From `git clone` to streaming tokens in one coffee. Typed Kotlin API, cold Flow, zero model archaeology. Try the hello-edgelm sample. #AndroidDev #Kotlin #DX #AI #onDeviceAI #opensource

## Day 7 — VISION + RECAP · all audiences
**Hook:** "Every Android phone is a supercomputer. It's time it ran AI."
**Beats:**
1. Fast recap montage of days 1–6 (private · offline · shared · fast · easy).
2. *"EdgeLM: the missing Android AI runtime."*
3. What's next ticker: *"GPU ⚡ · open-source 📖 · Hub 🧩 · your app 👉"*.
4. Dual CTA: Play badge + *"github.com/…/EdgeLM"*.
**Audio:** anthemic close. **Caption:** One week in, one idea proven: AI should run where your data lives. Live on Google Play, open to developers, and just getting started. 👻 #onDeviceAI #Android #privacy #opensource #AI #localLLM #buildinpublic

---

## Posting checklist
- Hook readable in the first 2s **with sound off** (big text, high contrast).
- Same caption's first line = the hook (that's the feed preview).
- 5–8 tags, mix broad (#AI #Android) + niche (#onDeviceAI #localLLM).
- Pin a comment with the Play link (keep links out of the caption body on IG/TikTok).
- Repurpose each reel's hook as an X post; Day 2/6 also → a dev-subreddit + LinkedIn.
- Day 5 & 7 drive **follows** (what's coming); Days 1/4 drive **installs**; Days 2/6 drive **integrations**.
