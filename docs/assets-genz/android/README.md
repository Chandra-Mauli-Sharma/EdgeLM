# EdgeLM — Android launcher icon (Goo Drip)

Complete, drop-in launcher icon set for the Goo Drip mark. Adaptive (API 26+),
legacy PNG fallbacks (pre-26), round variants, and a Play Store icon.

## Install (30 seconds)

1. Copy the **contents of `res/`** into your module's `src/main/res/` — e.g.
   `runtime-service/src/main/res/` or a demo app's `res/`. The `mipmap-*` folders
   merge with anything already there.
2. In that module's `AndroidManifest.xml`, on the `<application>` tag:

   ```xml
   <application
       android:icon="@mipmap/ic_launcher"
       android:roundIcon="@mipmap/ic_launcher_round"
       ... >
   ```
3. Rebuild + reinstall. Done — the launcher shows the Goo Drip.

> `runtime-service` is a background service, so it doesn't strictly need a launcher
> icon; this shines on a real EdgeLM consumer app or the demos. Add it wherever you
> want the brand to appear on the home screen.

## What's inside

```
res/
  mipmap-anydpi-v26/
    ic_launcher.xml          # adaptive icon (background + foreground layers)
    ic_launcher_round.xml
  mipmap-mdpi … xxxhdpi/
    ic_launcher_foreground.png   # the ghost mark, in the 66dp safe zone (transparent)
    ic_launcher_background.png   # obsidian gradient
    ic_launcher.png              # legacy square (pre-API 26)
    ic_launcher_round.png        # legacy circle (pre-API 26)
ic_launcher-playstore.png    # 512×512 — upload to Play Console
_src/                        # editable SVG sources for fg / bg
```

Density sizes: adaptive layers are 108dp (mdpi 108 → xxxhdpi 432 px); legacy icons
are 48dp (mdpi 48 → xxxhdpi 192 px). On API 26+ the system composites the fg/bg and
masks them to the device shape (circle, squircle, rounded square) — the mark is
scaled to survive any mask.

## Tweaks

- **Edit the mark**: change `_src/ic_launcher_foreground.svg` (or background) and
  re-export the PNGs at the sizes above.
- **Themed / monochrome icon** (Android 13+ tinted icons): add a single-color
  version at `drawable/ic_launcher_monochrome.xml` and reference it with
  `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>` inside the
  two `ic_launcher*.xml` files. Optional.
- **Solid background** instead of the gradient: replace the `<background>` drawable
  with `@color/ic_launcher_bg` (define `#0B0E10`) for a smaller build.
