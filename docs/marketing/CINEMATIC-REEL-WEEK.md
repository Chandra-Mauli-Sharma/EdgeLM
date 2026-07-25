# EdgeLM — Cinematic Reel Week (you on camera, shot on your phone)

Seven live-action reels **you film yourself** on your phone. "Cinematic" here means *how*
you shoot and edit — soft light, slow deliberate moves, shallow depth, and gentle
transitions — cut against **screen-recordings of EdgeLM** as b-roll. You're the narrator
and the face; the app is the co-star.

**Per reel:** 15–30s, vertical 9:16. Shoot in a clean, moody space (dark-ish background
with one soft light — it matches the brand's obsidian look). Edit in CapCut / CapCut-like.

---

## Shoot-it-cinematic kit (applies every day)

- **Camera:** rear camera, 4K 24fps if your phone offers it; **Cinematic/Portrait mode**
  for a soft, blurred background. Tap-and-hold to **lock focus + exposure** on your face
  so it doesn't hunt.
- **Framing:** vertical, eyes on the **upper third**, a little headroom, sit slightly
  off-centre (rule of thirds). Don't fill the whole frame with your face.
- **Movement:** one slow move per clip — a gentle push-in or a reveal. Two hands or a
  cheap gimbal. **Never pinch-zoom.**
- **Light:** face a **window or one soft lamp**; keep it to one side for shape. Avoid
  overhead light. Golden hour = instant warmth.
- **Background:** dark and simple with a small light source in it (a lamp, string lights,
  a screen glow) for depth. Clutter kills "cinematic."
- **Audio:** record in a **quiet room**, phone close or earbuds mic. Your voice is the
  star; music sits *low* underneath.
- **B-roll (record these once, reuse all week):** screen-record EdgeLM — the Playground
  streaming an answer, a model downloading, the offline demo, the notification. Also grab
  insert shots: your hands holding the phone, the screen glow on your face.
- **The soft segue (the part you asked about):** hide every cut with motion —
  1) **cover the cut with b-roll** (cut away to the phone screen, then back),
  2) **match-on-action** (start a hand move before the cut, finish it after),
  3) a **slow push-in that continues** across the cut, or
  4) a **0.3–0.5s cross-dissolve** in the editor. Speed-ramp gently into b-roll. No hard,
  jarring jump-cuts.
- **Grade:** cool the shadows slightly, keep skin warm, add subtle film grain, and use the
  **same look all seven days** so the set feels like one series.

Audience rota: Mon founder intro · Tue devs · Wed the big idea · Thu offline proof ·
Fri what's coming · Sat build-with-me · Sun manifesto.

---

## Day 1 — "I built this" · founder intro
**Hook (spoken + on-screen):** "I built an AI that runs entirely on your phone — no cloud."
**Setup:** you, soft side light, dark background, phone in hand. Start on your face, then
reveal the phone screen.
**Shots:**
1. Push-in on you: the hook line. *(cut hidden by lifting the phone into frame)*
2. B-roll: EdgeLM Playground streaming an answer. VO continues.
3. Back to you: *"No account. No internet. Nothing leaves the phone."*
4. Insert: airplane-mode toggle / the app still answering.
5. You, close: *"It's free on Google Play — link's right here."* → end card b-roll of the badge.
**Script:** "I spent months on this. It's an AI that runs *on* your phone — not in the cloud.
No account, no tracking, works with no internet. This is it answering, fully offline. It's
free on Google Play."
**Captions on screen:** "runs ON your phone" · "no cloud" · "100% offline" · "free on Google Play"
**Caption:** I built an AI that runs entirely on your phone — private, offline, free. Meet EdgeLM. 👻 Link in bio.
**Tags:** #onDeviceAI #buildinpublic #privacy #Android #indiedev

## Day 2 — "3 lines" · developers
**Hook:** "Adding on-device AI to your app is now three lines."
**Setup:** you at your desk, screen glow on your face; cut to a clean screen recording of
the editor.
**Shots:**
1. You, piece-to-camera hook. *(match-cut as you turn to the monitor)*
2. Screen-record: add the JitPack dependency → `EdgeLM.chat(...).collect { }` → tokens stream.
3. Back to you: *"No model files. No API keys. It just streams."*
4. Insert: the sample app answering on a phone next to the keyboard.
**Script:** "If you build Android apps — this is three lines. One dependency, call
`EdgeLM.chat`, collect the tokens. No model files, no cloud keys. Five minutes to your first
token. Sample's in the repo."
**Captions:** "1 dependency" · "3 lines" · "no cloud keys" · "hello-edgelm sample"
**Caption:** On-device AI in your app in ~5 minutes. Typed Kotlin API, streaming, zero model archaeology. Repo in bio. #AndroidDev #Kotlin #onDeviceAI
**Tags:** #AndroidDev #Kotlin #JitPack #buildinpublic #AI

## Day 3 — "One model, every app" · the big idea
**Hook:** "Every app on your phone ships its own AI. That's insane."
**Setup:** you, handheld slow walk or a slow push-in; conversational, a bit fired-up.
**Shots:**
1. You: the hook. *(soft dissolve on a gesture)*
2. You, gesturing: *"Ten apps, ten copies of the same model, gigabytes wasted."*
3. B-roll: two EdgeLM demo apps answering from one running service.
4. You, close: *"EdgeLM loads it once and shares it. One model. Every app."*
**Script:** "Here's the thing nobody talks about: every app that wants AI ships its own
engine and its own gigabytes. Ten apps, ten copies in memory. EdgeLM loads the model *once*
and every app shares it — a second app costs megabytes, not gigabytes. Inference should be
part of the phone, like the camera."
**Captions:** "every app = its own AI" · "one model, shared" · "+MB, not +GB"
**Caption:** The fix for on-device AI isn't a smaller model — it's *sharing* one. #onDeviceAI #systems #Android
**Tags:** #onDeviceAI #Android #AI #buildinpublic #tech

## Day 4 — "No signal, still works" · offline proof (film out in the world)
**Hook:** "Let's test it where every other AI dies — no signal."
**Setup:** shoot **on location** — a lift, a basement, a train, or literally airplane mode.
Handheld, real, documentary feel.
**Shots:**
1. You show the signal bars / flip airplane mode on-camera. *"Watch."*
2. Screen: type a prompt → it answers, no connection.
3. You, grinning: *"No wifi, no data, no cloud. It's all on the phone."*
4. Insert: the "100% offline" moment, phone in hand.
**Script:** "Every AI app needs the internet. This one doesn't. Airplane mode — on. No wifi,
no data. And… it still answers. Because the model lives on the phone. Plane, tunnel, dead
zone — doesn't matter."
**Captions:** "airplane mode ON" · "no wifi · no data" · "still answering"
**Caption:** Real AI with zero bars. ✈️ EdgeLM runs 100% offline once a model's downloaded. Free on Google Play. #offlineAI
**Tags:** #offlineAI #onDeviceAI #Android #privacy #nointernet

## Day 5 — "What's next" · teaser
**Hook:** "It's fast now. Next week it gets a lot faster."
**Setup:** you, calmer, moodier light; a "let you in on something" tone.
**Shots:**
1. You: the hook. *(slow push-in)*
2. Screen: current speed (tokens streaming), a subtle counter.
3. You: *"GPU acceleration is landing. Then the runtime goes open-source."*
4. Close: *"Follow — I'll show the before/after numbers."*
**Script:** "Right now it runs on the CPU and it's already quick. But GPU acceleration is
almost done — that's a big jump. After that the whole runtime goes open-source, and then the
model hub. Follow me and I'll show you the real before-and-after numbers."
**Captions:** "GPU acceleration → soon" · "going open-source" · "the Hub is next"
**Caption:** on-device AI is only getting faster — GPU offload + open-source incoming. 👻 Follow for the numbers. #buildinpublic #GPU
**Tags:** #buildinpublic #onDeviceAI #GPU #Android #comingsoon

## Day 6 — "Build with me" · integration, screen-led
**Hook:** "Let's add on-device AI to a fresh app — start the timer."
**Setup:** mostly screen recording with your voiceover; bookend with you on camera.
**Shots:**
1. You: *"Five minutes. Go."* (start a visible timer)
2. Screen: new project → add repo + dependency → `EdgeLM.chat().collect{}` → run.
3. Screen: the sample app streams a reply on a phone.
4. You: *"Done. That's it. Repo's in my bio."* (stop timer)
**Script:** "Fresh Android app, and I'm adding on-device AI live. Add the JitPack repo, one
dependency, call chat, collect the stream… run it. There — streaming tokens, on-device.
Under five minutes, no cloud. The whole sample is in my repo."
**Captions:** "0:00 start" · "1 dependency" · "streaming ✅" · "~5:00 done"
**Caption:** clone → streaming tokens in one coffee. hello-edgelm sample in the repo. #AndroidDev #Kotlin #buildinpublic
**Tags:** #AndroidDev #Kotlin #onDeviceAI #DX #opensource

## Day 7 — "Why I'm building this" · manifesto
**Hook:** "Your phone is a supercomputer. It's crazy that it can't run AI on its own."
**Setup:** night, moody single light, quiet and sincere — the emotional close of the week.
**Shots:**
1. You, still, low light: the hook. *(very slow push-in, no cuts if you can hold it)*
2. Soft dissolve → b-roll montage: the app, the offline moment, the code.
3. Back to you: *"AI should run where your data lives. That's the whole idea."*
4. Close: *"EdgeLM's live on Google Play, and it's open to developers. Just getting started."*
**Script:** "Your phone has a GPU, an NPU, gigabytes of RAM — and still, there's no built-in
way to run AI on it. Every app reinvents it, or ships your data to a cloud. I think AI should
run where your data lives — on the device, shared by every app. That's EdgeLM. It's live on
Google Play, it's open to developers, and honestly we're just getting started."
**Captions:** "a supercomputer in your pocket" · "AI where your data lives" · "live now"
**Caption:** One idea: AI belongs where your data lives — on your phone, shared by every app. Live on Google Play. 👻 #onDeviceAI #buildinpublic
**Tags:** #onDeviceAI #Android #privacy #buildinpublic #opensource

---

## Quick production checklist
- Film **all your on-camera bits in one session** (same outfit/light = a consistent series).
- Record the **screen b-roll once** and reuse it across Days 1, 2, 4, 6.
- First **2 seconds = the hook**, readable with sound off (burn the hook as on-screen text).
- Keep one **grade + one font** for captions all week.
- Music low under your voice; pick something with a soft build, not a beat drop.
- End cards: your face → the **official Google Play badge** (in `docs/marketing/reels/assets/`).
- Pin the Play link in a comment; put the repo link for Days 2/6 in bio.
