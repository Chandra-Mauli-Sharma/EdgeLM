# Phase 1 — OpenAI-compatible HTTP shim

This adds the **compatibility surface** from Part 5 of the architecture doc: a
localhost HTTP server inside the runtime-service that speaks the OpenAI API, so
cURL, the OpenAI SDKs, and any external tool work by pointing `base_url` at
`http://localhost:1408/v1`. Your Binder path is unchanged — this runs *alongside*
it, in the same service process, calling the same engine directly (no Binder hop).

Copy-ready file: `docs/phase1-http/EdgeLMHttpServer.kt`.

Endpoints:
- `POST /v1/chat/completions` — `stream=true` → SSE, otherwise one JSON object
- `GET /v1/models`
- `GET /health`

Works with either the Phase 0 placeholder or the real llama.cpp engine — it just
calls `NativeBridge.generate`.

---

## Step 1 — Copy the server in

```powershell
copy docs\phase1-http\EdgeLMHttpServer.kt runtime-service\src\main\java\ai\edgelm\service\
```

## Step 2 — Add the dependency (`runtime-service/build.gradle.kts`)

```kotlin
dependencies {
    implementation(project(":contract"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")   // <-- add
}
```

## Step 3 — Add the INTERNET permission (`runtime-service/src/main/AndroidManifest.xml`)

Binding a loopback `ServerSocket` still needs it. Add above `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Step 4 — Start/stop the server in `EdgeLMService.kt`

Add a field, one lock, start it after the model loads, and stop it in `onDestroy`.

```kotlin
// fields
private val inferenceLock = Any()
private var http: EdgeLMHttpServer? = null

// at the end of onCreate(), after NativeBridge.loadModel(...)
http = EdgeLMHttpServer(
    port = 1408,
    infer = { _, prompt, onToken, isCancelled ->
        // Serialize with Binder requests: one shared engine.
        synchronized(inferenceLock) {
            val start = System.currentTimeMillis()
            val sink = object : NativeBridge.TokenSink {
                override fun onChunk(text: String) = onToken(text)
                override fun isCancelled(): Boolean = isCancelled()
            }
            val n = if (modelHandle != 0L) NativeBridge.generate(modelHandle, prompt, sink) else 0
            EdgeLMHttpServer.GenStats(n, System.currentTimeMillis() - start)
        }
    },
    warmModels = { if (modelHandle != 0L) listOf("model.gguf") else emptyList() }
).also {
    runCatching { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        .onFailure { e -> android.util.Log.e("EdgeLMService", "http start failed", e) }
}

// in onDestroy(), before super.onDestroy()
http?.stop()
```

> The `synchronized(inferenceLock)` makes HTTP and Binder requests take turns on
> the single engine. During a long SSE stream that briefly blocks other callers —
> fine for the spike; the real scheduler (Part 8) replaces this with priority +
> token-boundary preemption.

## Step 5 — Build, install, expose the port

```powershell
.\gradlew.bat :runtime-service:installDebug
# make sure the service is running (open a demo app once, or it starts on first bind)
adb forward tcp:1408 tcp:1408      # PC:1408 -> device:1408
```

## Step 6 — Hit it from your PC

Health + models:

```powershell
curl.exe http://localhost:1408/health
curl.exe http://localhost:1408/v1/models
```

Chat (non-streaming). PowerShell mangles inline JSON, so put the body in a file:

```powershell
@'
{ "model": "default",
  "messages": [{ "role": "user", "content": "Say hi in one short sentence." }] }
'@ | Set-Content -Encoding utf8 body.json

curl.exe http://localhost:1408/v1/chat/completions -H "Content-Type: application/json" -d "@body.json"
```

Streaming (SSE) — `-N` disables curl buffering so you see tokens arrive live:

```powershell
@'
{ "model": "default", "stream": true,
  "messages": [{ "role": "user", "content": "Count to five." }] }
'@ | Set-Content -Encoding utf8 stream.json

curl.exe -N http://localhost:1408/v1/chat/completions -H "Content-Type: application/json" -d "@stream.json"
```

You should see `data: {chat.completion.chunk ...}` lines stream in, ending with
`data: [DONE]`.

## Step 7 — Prove OpenAI-SDK compatibility (the actual point)

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:1408/v1", api_key="not-needed")
r = client.chat.completions.create(
    model="default",
    messages=[{"role": "user", "content": "One sentence on why local AI matters."}],
    stream=True,
)
for chunk in r:
    print(chunk.choices[0].delta.content or "", end="", flush=True)
```

If that streams, you've shown migration is a **one-line base_url change** — the
adoption wedge from the strategy doc, working on-device.

---

## Notes, limits, and what's next

- **Loopback + no auth + no per-app identity.** Unlike Binder (which carries the
  caller UID for the permission broker), the HTTP path can't identify the calling
  app. Keep it bound to `127.0.0.1`; gate it behind the permission broker before
  it's ever more than a dev convenience.
- **On-device apps** using an OpenAI client library can hit `http://127.0.0.1:1408/v1`
  directly — no `adb forward` (that's only for reaching the phone from your PC).
- **Message flattening is naive** for the spike (turns concatenated, native applies
  the chat template). Phase 1 passes structured turns and applies the model's real
  template server-side.
- **One engine, serialized.** Concurrency is a mutex today; the scheduler makes it
  fair and preemptible later.
- **CORS** is permissive (`*`) for easy local tooling; tighten for anything real.

Next: `docs/PHASE1-VULKAN-GPU.md` to move inference onto the GPU and re-measure.
