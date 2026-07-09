# EdgeLM — Screenshot Guide

Play requires **2–8 phone screenshots** (PNG/JPG, 16:9 or 9:16, min side ≥ 320 px,
max ≥ 3840 px). Capture these five on a real device for the strongest listing.

## Recommended shots (in order)

1. **Model picker** — the main runtime screen showing the catalog with a model
   marked "Active ✓". Shows the core value: choose a model.
2. **Playground streaming** — a reply mid-stream with the tokens/sec line. Proves it
   actually runs on-device.
3. **Download in progress** — a model card downloading, ideally with the download
   notification pulled down. Shows the download experience.
4. **Runtime notification** — the ongoing "EdgeLM Runtime" notification with a
   "Generating for …" or "Ready · … tok/s" line and the Free memory / Stop actions.
5. **Splash / branding** *(optional)* — the branded splash, or a clean shot of the
   header with the ghost + wordmark.

## How to capture (device connected via USB)

```powershell
# one screenshot straight to a file
adb exec-out screencap -p > shot1.png

# repeat for each screen (rename shot2.png, shot3.png, …)
```

Or use the phone's own screenshot (Power + Volume-Down) and copy the PNGs off the
device.

## Tips

- Use a device with enough RAM to show a mid-size model as "Active (warm)".
- Download a model first so the picker shows real state, and send one Playground
  prompt so the tokens/sec line is populated.
- Keep the status bar clean (full battery, no clutter) if you can.

## Optional polish

Once you've captured the raw PNGs, I can add device frames and short caption banners
(brand-styled) to turn them into marketing screenshots — just drop the raw files in
`docs/play/screenshots/` and ask.
