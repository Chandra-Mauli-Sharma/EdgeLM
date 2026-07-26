# Phase 2 — System-Prompt Support

Closes a real OpenAI-compat gap: the API's `system` message was being **ignored**. The
native path hardcoded `SYSTEM_TEXT` ("You are a concise, helpful assistant") and dumped
any `system` message into the user turn via `flattenMessages`. Now a caller's system
prompt is the actual system prefix — which also makes tool-calling more reliable (the
tool instructions now live in the system prompt, not the user turn).

## How it works

- **Native** (`llama_runner.cpp`): `Model.system_text` (empty = built-in default).
  `set_system_prompt(m, s)` updates it and, on change, marks the system prefix for
  re-prefill and invalidates the session (the system prompt is the start of every
  sequence's KV). `ensure_system` wraps the content in the chat template
  (`<|im_start|>system\n…<|im_end|>\n`) and prefills it. The speculative / lookup decode
  paths inherit it (they call `ensure_system`).
- **JNI / `NativeBridge` / `InferenceEngine`**: `setSystemPrompt` (default no-op, so
  engines that don't support it — LiteRT — just ignore it).
- **Service**: `runInference` calls `engine.setSystemPrompt(session, system)` under the
  scheduler lock, right before `generate` — atomic with it.
- **HTTP**: `extractSystem(messages)` pulls the `system` message(s); `flattenMessages`
  now skips them. Tool-calling puts the tool schemas **in the system prompt**
  (`system + preamble`), which is more reliable than the old user-turn placement.

## Scope / notes

- **Default (shipping) path only.** The experimental batched engine uses its own COW
  system template; per-request system prompts aren't applied there yet.
- **Binder/SDK path** keeps the default system prompt (the AIDL `submit` has no system
  param — a later addition). This slice targets the OpenAI HTTP surface, where `system`
  matters most.
- Changing the system prompt re-prefills it (fast, ~tens of ms); a stable system prompt
  is cached after the first request, same as before.

## Try it

```
# tool-calling now sends the preamble as the system prompt:
.\tools\edgelm.ps1 tools-demo "What's the weather in Paris?"

# a custom system message via curl (persona):
curl http://127.0.0.1:1408/v1/chat/completions -H "Content-Type: application/json" -d '{
  "messages": [
    {"role":"system","content":"You are a pirate. Answer in pirate speak."},
    {"role":"user","content":"How do I make tea?"}
  ]}'
# -> content should be in pirate voice (was ignored before this change)
```

First compile of the native `set_system_prompt` + `system_block` path — verify against the
pinned llama.cpp that re-prefill on system change behaves.
