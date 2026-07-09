# EdgeLM — Play Release Kit

Everything needed for the Play Console listing lives in this folder.

| File | Use |
|---|---|
| `PRIVACY.md` | Privacy policy — host it and paste the URL into the Console |
| `STORE-LISTING.md` | App name, short & full description, category |
| `DATA-SAFETY.md` | Data Safety answers + permission & FGS justifications |
| `SCREENSHOTS.md` | What to capture and how |
| `feature-graphic-1024x500.png` | Play feature graphic (opaque, ready to upload) |
| `../assets-genz/android/ic_launcher-playstore.png` | 512×512 app icon |

## Hosting the privacy policy

Play requires a publicly reachable **URL** (a file isn't enough). Easiest options:

- **GitHub Pages** — push this repo, enable Pages, and link to the rendered
  `docs/play/PRIVACY.md` (or convert it to `privacy.html`).
- **GitHub Gist** — paste the contents into a public gist and use its URL.
- **Google Sites / any free static host** — paste the text onto a page.

Whichever you pick, put the final URL in both the listing's *Privacy policy* field and
in `STORE-LISTING.md`.

## Submission checklist

1. **Build the signed bundle** — `.\gradlew.bat :runtime-service:bundleRelease`
   (needs `keystore.properties` at the repo root; see `keystore.properties.template`).
   Output: `runtime-service/build/outputs/bundle/release/runtime-service-release.aab`.
2. **Create the app** in Play Console (default language, app name from
   `STORE-LISTING.md`).
3. **Main store listing** — short & full description, app icon (512), feature graphic
   (1024×500), 2–8 phone screenshots (see `SCREENSHOTS.md`).
4. **Privacy policy** — paste the hosted URL.
5. **Data safety** — answer per `DATA-SAFETY.md` (No data collected / No data shared).
6. **App content** — content rating questionnaire (Everyone), target audience, ads
   (No), news (No), government (No).
7. **Foreground service** — when prompted about the `specialUse` FGS, paste the
   justification from `DATA-SAFETY.md`.
8. **Upload the `.aab`** to a testing track first (Internal testing), add yourself as
   a tester, and review the **pre-launch report** for crashes/policy flags.
9. Fix anything the pre-launch report surfaces, then promote to Production.

## Pre-flight sanity checks

- Install a **release** build and open the Playground — generate a reply to confirm
  R8 didn't strip the JNI bridge (an `UnsatisfiedLinkError` here means the keep-rules
  need a look).
- Confirm the release build has **no** open `127.0.0.1:1408` port (HTTP shim is
  debug-only by design).
- Bump `versionCode` for every new upload (currently `1`, versionName `0.1.0`).
