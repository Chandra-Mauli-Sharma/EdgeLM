# EdgeLM — Privacy Policy

**Effective date:** 9 July 2026
**App:** EdgeLM Runtime (`ai.edgelm.runtime`)
**Contact:** chandramaulisharma26@gmail.com

## Summary

EdgeLM is an on-device AI runtime. It runs language models **locally on your
device**. It does not have user accounts, does not show ads, and does not collect,
store, or transmit your personal data. Prompts and generated text never leave your
device.

## What we collect

**Nothing.** EdgeLM does not collect, log, or transmit any personal or usage data.
There is no analytics SDK, no advertising SDK, no crash-reporting service, and no
account system.

## How your content is handled

- **Prompts and responses** — Text you enter in the built-in Playground, and
  prompts sent to the runtime by other apps on your device, are processed entirely
  in memory on your device to generate a response. They are **not written to disk,
  not stored, and not sent anywhere**. When a request finishes (or the model is
  unloaded), that working memory is released.
- **Conversation context** — Multi-turn context is held only in volatile memory for
  the duration of a session and is discarded when the session is cleared or the
  runtime stops.

## Network use

EdgeLM makes network requests for **one purpose only**: to download the AI model
file **you explicitly choose** from the in-app catalog. These downloads are fetched
over HTTPS from third-party model hosts (for example, Hugging Face). Only a standard
file request is made — no personal data, prompts, or identifiers are sent by EdgeLM.
The host you download from may record standard request metadata (such as your IP
address) under its own privacy policy; please refer to that host's policy. If you
never download a model, EdgeLM makes no network requests for inference.

EdgeLM performs **no cloud inference** — model execution is always local.

## Permissions

- **Internet** — to download the model file you select.
- **Foreground service / data sync / special use** — to keep the shared runtime and
  an in-progress model download running reliably, with a visible notification, while
  in use.
- **Post notifications** — to show the runtime status and download progress.

EdgeLM requests no access to your location, contacts, camera, microphone, photos,
messages, or files outside its own private app storage.

## Data sharing

EdgeLM does not share any data with third parties, because it does not collect any.

## Children

EdgeLM does not knowingly collect data from anyone, including children. It contains
no ads and no data collection.

## Changes to this policy

If this policy changes, the updated version will be posted at the same URL with a new
effective date.

## Contact

Questions about this policy: **chandramaulisharma26@gmail.com**
