# Hello EdgeLM — 5-minute quickstart

A complete, standalone Android app that adds **on-device AI** in three calls. Type a
prompt, get a streamed answer — running entirely on the phone, no cloud, no API keys,
no model files to ship.

This project depends on the EdgeLM SDK straight from JitPack, so it's exactly what
your own app would do. Copy it, rename the package, and you're integrated.

## What you need

- Android Studio (or the Gradle CLI) and a device/emulator on **Android 8.0+ (API 26)**.
- The **EdgeLM Runtime** app installed on that device — the shared engine that actually
  runs the model. Get it here:
  [play.google.com/store/apps/details?id=ai.edgelm.runtime](https://play.google.com/store/apps/details?id=ai.edgelm.runtime).
  Open it once and download a model (the app recommends one for your phone).
  *(If it isn't installed, this sample sends you to the Play page automatically.)*

## Run it

```bash
git clone https://github.com/Chandra-Mauli-Sharma/EdgeLM
cd EdgeLM/samples/hello-edgelm
./gradlew installDebug      # or open this folder in Android Studio and press Run
```

Launch **Hello EdgeLM**, keep the default prompt or type your own, and hit **Send**.
You'll see the answer stream in token by token.

## The whole integration

Two pieces — a dependency and three method calls.

**1. Add the SDK** (already done in this sample):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.Chandra-Mauli-Sharma.EdgeLM:sdk:0.1.0")
}
```

No `AndroidManifest.xml` changes — the SDK merges the `ai.edgelm.permission.USE_RUNTIME`
permission and the runtime package `<queries>` for you.

**2. Call it** (the heart of [`MainActivity.kt`](app/src/main/java/com/example/helloedgelm/MainActivity.kt)):

```kotlin
// Is the shared runtime present? If not, send the user to Play.
when (EdgeLM.status(context)) {
    EdgeLM.Status.NOT_INSTALLED -> EdgeLM.promptInstall(context)
    EdgeLM.Status.AVAILABLE     -> EdgeLM.initialize(context)   // bind once
}

// Stream a completion. Cold Flow: work starts on collect, cancels with the scope.
lifecycleScope.launch {
    EdgeLM.chat(
        model = "default",
        prompt = "Explain on-device AI in one sentence",
        sessionId = "hello",          // stable id ⇒ multi-turn memory
        priority = EdgeLM.FOREGROUND,
    ).collect { token -> output.append(token) }
}
```

That's it. Pass the same `sessionId` on later calls and the runtime keeps the
conversation in its warm KV cache, so follow-ups don't re-process earlier turns.

## Where to go next

- **Full API reference:** [`docs/INTEGRATION.md`](../../docs/INTEGRATION.md) — sessions,
  priority classes, lifecycle, and error handling.
- **Why a shared runtime:** one model is mmap'd once and served to every EdgeLM app on
  the device, so a second integrating app adds almost no memory.
