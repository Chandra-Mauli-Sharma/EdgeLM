# Integrating EdgeLM in your app

Add on-device AI to any Android app in a few lines. Your app calls the **shared
EdgeLM runtime** (a separate app the user installs from Google Play) — you never ship
or manage model weights, and the model stays resident in memory shared across every
EdgeLM-powered app.

> **In a hurry?** There's a complete, runnable sample at
> [`samples/hello-edgelm`](../samples/hello-edgelm) — clone it, `./gradlew installDebug`,
> and you have a working streaming chat app to copy from.

## 1. Add the dependency (JitPack)

In `settings.gradle.kts` (or your root `build.gradle`), add the JitPack repo:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Chandra-Mauli-Sharma.EdgeLM:sdk:0.1.0")
}
```

The `:contract` (AIDL/Binder types) comes in transitively. **No manifest changes
needed** — the SDK declares the `ai.edgelm.permission.USE_RUNTIME` permission and the
package `<queries>` for you via manifest merge.

> minSdk 26 · the SDK is tiny (Binder client + coroutines); all the heavy lifting
> lives in the runtime app.

## 2. Make sure the runtime is installed

EdgeLM is a shared runtime, so the **EdgeLM Runtime app must be installed** (the user
gets it once from Play; every EdgeLM app then shares it). Check up front and prompt if
missing:

```kotlin
when (EdgeLM.status(context)) {
    EdgeLM.Status.NOT_INSTALLED -> EdgeLM.promptInstall(context)  // sends user to Play
    EdgeLM.Status.AVAILABLE     -> EdgeLM.initialize(context)     // bind the service
}
```

`promptInstall` opens the runtime's Play page (`market://` with a web fallback).

## 3. Stream a completion

`chat(...)` returns a **cold `Flow<String>`** of tokens — work starts on `collect`, and
cancelling the collector's scope cancels the on-device decode.

```kotlin
EdgeLM.initialize(context)          // once (e.g. Application.onCreate)

lifecycleScope.launch {
    try {
        EdgeLM.chat(
            model = "default",       // Phase 0 serves the active model; value is advisory
            prompt = "Explain on-device AI in one line",
            sessionId = "chat-1",    // stable id = multi-turn; "" = stateless one-shot
            priority = EdgeLM.INTERACTIVE,
        ).collect { token ->
            appendToUi(token)        // tokens stream in
        }
    } catch (e: EdgeLMUnavailableException) {
        // runtime missing or not responding — fall back / prompt install
    }
}
```

### Multi-turn conversations

Pass a stable `sessionId`. Prior turns stay in the runtime's warm KV cache and aren't
re-prefilled, so follow-ups are fast:

```kotlin
EdgeLM.chat("default", "My name is Ada.", sessionId = "s1").collect { … }
EdgeLM.chat("default", "What's my name?", sessionId = "s1").collect { … }  // remembers
```

### Priority

Requests are admitted to the single shared engine by priority (with aging):

```
EdgeLM.FOREGROUND (3)  // user is staring at the result
EdgeLM.INTERACTIVE (2) // default
EdgeLM.BATCH (1)
EdgeLM.BACKGROUND (0)  // best-effort background work
```

## 4. Lifecycle

- `EdgeLM.initialize(context)` — idempotent; binds the runtime service.
- `EdgeLM.warmModels()` — `suspend`, returns models currently loaded (diagnostics).
- `EdgeLM.shutdown()` — unbind when you're done (optional; the runtime stays alive for
  other apps).

## Notes

- **The user picks/downloads models in the EdgeLM Runtime app**, not in yours. Your app
  just requests generations; whatever model is active serves them.
- Everything runs **on-device** — prompts and responses never leave the phone.
- If `status()` is `NOT_INSTALLED`, always give the user a path (`promptInstall`) rather
  than failing silently.

## Publishing note (maintainers)

JitPack builds from a git tag. To release a version, tag it to match the SDK version
(the module publishes `version = "0.1.0"`), e.g. `git tag 0.1.0 && git push origin 0.1.0`,
then the coordinates above resolve. JitPack build log: `https://jitpack.io/#Chandra-Mauli-Sharma/EdgeLM`.
