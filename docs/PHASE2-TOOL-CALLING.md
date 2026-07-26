# Phase 2 — OpenAI Tool / Function Calling

First slice of the plugin platform (arch-doc Part 9). Lets an app pass `tools` to
`/v1/chat/completions` and get back OpenAI-shaped `tool_calls`, so the model can drive
actions. This slice does **prompt-based** tool calling and returns the call for the *app*
to execute (the standard OpenAI loop). Executing tools *inside* the runtime — the
sandboxed MCP plugin broker — is the larger follow-on.

## How it works

EdgeLM's small chat models aren't natively tool-trained, so it's done by prompt:

1. `ToolCalls.preamble(tools)` formats the tool JSON-Schemas into an instruction that tells
   the model to reply with `{"tool_call": {"name": ..., "arguments": {...}}}` when a tool
   is needed, else answer normally. It's prepended to the prompt.
2. The request runs **non-streaming** (we need the full completion to detect a call).
3. `ToolCalls.parse(output)` tolerates ```json fences / surrounding text, extracts the
   `tool_call`, and returns it as OpenAI `tool_calls` (with `arguments` as a JSON string).
4. `EdgeLMHttpServer.toolResponse` returns `finish_reason: "tool_calls"` + the call, or
   plain `content` + `stop` if no tool was requested.

The app executes the tool and sends the result back as a `tool` message — the normal
OpenAI multi-turn tool loop.

## Try it

```
.\tools\edgelm.ps1 tools-demo "What's the weather in Paris?"
# -> tool_call: get_weather({"city": "Paris"})

.\tools\edgelm.ps1 tools-demo "Tell me a joke"
# -> content: <a joke>   (no tool needed)
```

The demo sends a canned `get_weather` tool. Any OpenAI client works too — POST `tools`
to `/v1/chat/completions`.

## Files

- `ToolCalls.kt` — preamble builder + tolerant tool-call parser.
- `EdgeLMHttpServer.kt` — `chatCompletions` routes to `toolResponse` when `tools` present.
- `tools/edgelm{,.ps1}` — `tools-demo` command.

## Grammar-constrained calls (tool_choice)

When the caller sets OpenAI `tool_choice` to `"required"` or a specific
`{"type":"function","function":{"name":"X"}}`, EdgeLM builds a **GBNF grammar**
(`ToolCalls.grammarForTools`) and constrains decoding to it (`set_grammar` → a
`llama_sampler_init_grammar` at the front of the sampler chain). The output is then
*guaranteed* to be `{"tool_call": {"name": <allowed>, "arguments": <valid JSON>}}` — a
well-formed call on **any** model, including the 0.5B. `tool_choice: "auto"` (default)
leaves the model free to decide via the preamble (no grammar).

Arguments are constrained to valid JSON, not yet the per-tool schema — schema-strict
arguments via llama.cpp's `json-schema-to-grammar` is the next refinement.

## Notes / limits (this slice)

- **Auto mode reliability scales with model size.** Without a forcing `tool_choice`, small
  models follow the JSON instruction imprecisely (the parser is tolerant); 1B/3B are
  reliable. Use `tool_choice: "required"` for a guaranteed call on any model.
- Tools force non-streaming. Streaming `tool_calls` deltas are a later addition.
- No parallel/multi tool calls yet (returns the first). 
## Agent loop — the runtime executes tools (`ToolBroker`, first slice)

Beyond returning a call for the app to run, the runtime can run tools itself and loop:

- `ToolBroker` registers safe **built-in** tools — `calculator` (a hand-written
  arithmetic parser, no code-eval) and `current_time`. No permissions needed.
- `POST /v1/edge/agent {"prompt":"..."}` → the service injects the built-in tools, and if
  the model emits a `tool_call` for one, **executes it in-runtime**, feeds the result back,
  and re-generates — up to 4 steps — then returns `{answer, steps}`.
- CLI: `edgelm agent "what is (2+3)*4?"` → `[tool] calculator -> 20` then `answer: ...`.

```
.\tools\edgelm.ps1 agent "what is (17 * 23) + 5?"
#   [tool] calculator -> 396
#   answer: (17 * 23) + 5 = 396
.\tools\edgelm.ps1 agent "what time is it?"
#   [tool] current_time -> Sat, 26 Jul 2026 ...
```

This is the agent thesis in miniature: the model *reasons*, the runtime *acts*.

### Consent gate for side-effecting tools

Tools are classified `sideEffecting` or not. Read tools (`calculator`, `current_time`)
always run. A side-effecting tool (`remember`, which writes a note to disk) runs **only if
the caller passes `allow_side_effects: true`** — otherwise the agent loop refuses it and
feeds the refusal back to the model. This is Part 9's "write/actuating tools require a
confirmation surface", human-in-the-loop.

```
.\tools\edgelm.ps1 agent "remember that my meeting is at 3pm"
#   [tool] remember -> refused: 'remember' has side effects and needs consent ...
# with consent (curl):  POST /v1/edge/agent {"prompt":"...","allow_side_effects":true}
#   [tool] remember -> saved
```

## App-registered external tools (`AppToolRegistry`, the MCP-server host)

Apps extend the agent with their own tools, registered as **HTTP webhooks** — the
MCP-server model (Part 9). This is what makes EdgeLM a *platform*: the runtime doesn't
ship every tool; apps bring their own.

- `POST /v1/edge/tools/register {name, description, parameters, url}` — register a tool.
  `GET /v1/edge/tools` lists them; `POST /v1/edge/tools/unregister {name}` removes one.
- The agent merges registered tools with the built-ins. When the model calls one, the
  runtime **POSTs the arguments to the tool's `url`** and feeds the response back into the
  loop. Registered tools are side-effecting → consent-gated (`allow_side_effects`).
- CLI: `edgelm tools register weather http://.../weather "get weather"`, `edgelm tools list`.

```
# a trivial local webhook: any server that echoes {"result": "..."} for POST {arguments}
edgelm tools register echo http://10.0.2.2:9000/echo "echo the input back"
edgelm agent "use the echo tool on the word banana"   # (with allow_side_effects=true)
#   [tool] echo -> banana
```

## Data-flow firewall v1

The agent now separates two consents (Part 7/9):

- **`allow_side_effects`** — a tool acts *locally* (e.g. `remember` writes to disk).
- **`allow_egress`** — a tool sends data *off-device* (an external webhook). Stricter,
  because it's the exfiltration surface.

External (webhook) tools require `allow_egress`, not just `allow_side_effects` — so a local
tool call and "data leaves the phone" are distinct decisions. And every egress call is
**legible**: the agent step records the destination URL (`"egress": "<url>"`) and the
runtime logs `EGRESS: <tool> -> <url> sent=<args>`, so you can always see exactly what left.

```
edgelm agent "echo the word banana" --allow-egress   # webhook tool needs egress consent
#   [tool] echo -> banana   (egress -> http://192.168.1.42:9000/echo)
```

Still ahead (full firewall): **taint-tracking** — marking data read under a local-only
capability (RAG context, local tool results) so the runtime can *block* it from flowing to
an egress tool without fresh consent, not just report it. Plus a per-app consent UI and
sandboxed *in-process* plugins. The `allow_egress` gate + per-step egress record are the
seams those build on.
