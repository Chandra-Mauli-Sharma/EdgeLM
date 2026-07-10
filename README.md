# EdgeLM — AI that runs on your phone

<p align="center">
  <img src="docs/play/feature-graphic-1024x500.png" alt="EdgeLM — The Android AI Runtime" width="100%">
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=ai.edgelm.runtime"><b>Get it on Google Play</b></a>
</p>

EdgeLM is a shared, on-device AI runtime for Android. It runs language models
**directly on your phone** — privately, offline, and shared by every app that wants to
use AI. Download a model once, and any EdgeLM-powered app can use it, instead of each
app bundling and running its own.

Think of it as a system service for on-device intelligence: one place that holds the
model, so your apps get AI without sending your data to the cloud.

## Screenshots

<p align="center">
  <img src="docs/play/screenshots/marketing/01_recommended.png" width="180" alt="The right AI for your phone" />
  <img src="docs/play/screenshots/marketing/02_playground.png" width="180" alt="Chat privately, on your device" />
  <img src="docs/play/screenshots/marketing/03_models.png" width="180" alt="Choose your assistant" />
  <img src="docs/play/screenshots/marketing/04_offline.png" width="180" alt="Fast, and fully offline" />
  <img src="docs/play/screenshots/marketing/05_advanced.png" width="180" alt="Full control when you want it" />
</p>

## Why EdgeLM

- **Private by design.** Your prompts and the AI's responses never leave your device.
  No cloud, no account, no tracking, no ads.
- **Works offline.** Once a model is downloaded, EdgeLM runs with no internet at
  all — the same on a plane, in a tunnel, or on airplane mode.
- **Shared and efficient.** EdgeLM loads a model once and serves every app from that
  single copy, so a second app adds almost no extra memory.
- **No cloud cost, no round-trips.** Nothing to pay per request, and no waiting on a
  network.

## How it works

1. **Install EdgeLM Runtime** and follow the quick welcome.
2. **Pick an AI.** EdgeLM recommends the right model for your phone — one tap to
   download. Prefer to choose yourself? Switch to Advanced for the full catalog.
3. **Use it.** Try it right away in the built-in playground, and any EdgeLM-powered app
   on your phone can now use on-device AI.

A small, always-available notification shows when the runtime is active, lets you free
up memory on demand, and the runtime automatically releases memory when idle.

## Choose your model

A curated catalog spans tiny-and-fast to more capable, each shown with a plain-language
description, size, and what it's best for — with a warning if your phone may not have
enough memory:

| In the app | Model | Good for |
|---|---|---|
| Quick Assistant | Qwen2.5 0.5B | Fast, light — quick questions on any phone |
| Everyday Assistant | Llama 3.2 1B | Chatting, writing, summarizing |
| Smart Assistant | Qwen2.5 1.5B | Sharper answers, better multilingual |
| Pro Assistant | Llama 3.2 3B | Higher-quality writing and thinking |
| Expert Assistant | Phi-3.5 mini | Tricky questions, math, coding help |

Keep several installed and switch between them instantly, with no re-download.

## Privacy

EdgeLM collects no personal data. Prompts and responses are processed on your device
and are never stored or sent anywhere. The only time EdgeLM uses the network is to
download the model you choose. Full policy: [`docs/play/PRIVACY.md`](docs/play/PRIVACY.md).

## For developers

EdgeLM exposes a tiny, OpenAI-style API so your app can call on-device AI in a few
lines — without shipping or managing model weights yourself:

```kotlin
EdgeLM.chat(prompt = "Hello", sessionId = "chat-1") { token -> /* stream */ }
```

Apps request access through a dedicated permission, and the runtime serves them by
priority. Build, integration, and architecture details are in
[`docs/`](docs/) — see the release kit in [`docs/play/`](docs/play/).

---

*On-device. Private. Offline. Shared by every app.*
