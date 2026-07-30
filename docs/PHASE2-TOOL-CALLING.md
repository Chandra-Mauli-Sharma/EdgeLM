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
#   [tool] echo -> banana   (egress -> http://127.0.0.1:9000/echo)
```

### Reproducing the egress demo (adb reverse — no firewall/admin)

A physical device can't reach the dev machine's LAN IP if Windows Firewall drops the
inbound port (a 10s **timeout**, vs. an instant "connection refused" when the server is
actually down). Rather than adding a firewall rule (needs admin
`New-NetFirewallRule -LocalPort 9000 -Protocol TCP -Direction Inbound -Action Allow`),
tunnel the port over adb — the device hits its *own* loopback, which forwards to the PC:

```
python tools/echo_webhook.py                    # binds 0.0.0.0:9000 on the PC
adb reverse tcp:9000 tcp:9000                    # device 127.0.0.1:9000 -> PC 127.0.0.1:9000
edgelm tools register echo http://127.0.0.1:9000/echo "echo the input"
edgelm agent "echo the word banana" --allow-egress
#   [tool] echo -> echo: {"value":"banana"}   (egress -> http://127.0.0.1:9000/echo)
#   answer: banana
```

No LAN IP, no firewall rule, no admin. Loopback is also exempt from the cleartext-HTTP
restriction, so the network-security config isn't a factor. Validated on device 2026-07-26.

## Data-flow firewall v2 — taint-tracking

v1 *logged* egress; v2 *blocks* local data from leaving. The agent now tracks a **taint
set**: the result of any local, non-pure built-in tool (`recall` reads notes back off the
device, file/RAG reads — but not pure/public `calculator`/`current_time`) is marked
local-origin data. (Note a *write* like `remember` returns only `"saved"`; it's the *read*
that surfaces the sensitive bytes, which is what taint tracks.) Before an egress
webhook fires, its argument JSON is scanned for tainted spans (`taintSpansIn`: verbatim
substring **or** a distinctive ≥5-char word, to catch the model paraphrasing local data into
the call). If local data would leave the device, the call is **refused** unless the caller
passes the strictest consent, **`allow_tainted_egress`** — separate from `allow_egress`.

So there are now three nested decisions, weakest to strongest:

- **`allow_side_effects`** — a tool acts locally.
- **`allow_egress`** — a tool reaches the network at all.
- **`allow_tainted_egress`** — an egress call may carry *your local data* off-device (exfiltration).

Every carrying step is legible: the agent step records `"tainted_egress": true`, the CLI tags
it `[TAINTED]`, and the runtime logs `TAINTED EGRESS: <tool> carried N local span(s)`.

```
edgelm agent "remember the word banana" --allow            # seed a local note (write)
# read that local note back, then try to ship it out — blocked without --allow-tainted:
edgelm agent "recall my notes and echo them" --allow-egress
#   [tool] recall -> banana
#   [tool] echo  -> refused: 'echo' would send LOCAL data off-device (1 tainted span ...) ...
# with the exfiltration consent:
edgelm agent "recall my notes and echo them" --allow-egress --allow-tainted
#   [tool] recall -> banana
#   [tool] echo  -> echo: {"value":"banana"}   (egress -> ...) [TAINTED]
```

### Deterministic self-test (`firewall-test`)

A small chat model won't reliably chain `recall -> echo`, so the end-to-end agent demo is
flaky *through no fault of the firewall*. `edgelm firewall-test` verifies the gate directly —
no model in the loop: it seeds a tainted span `"banana"`, builds a synthetic egress call
carrying it, and runs the **same** `egressRefusal` gate the agent uses, across all three
consent states:

```
edgelm firewall-test
#   [no consent      ] BLOCKED: 'echo' sends data off-device (network egress) — needs allow_egress
#   [egress only     ] BLOCKED: 'echo' would send LOCAL data off-device (1 tainted span ...) — needs allow_tainted_egress
#   [egress + tainted ] ALLOWED: echo: {"value":"banana"}  (egress -> http://127.0.0.1:9000/echo)
```

The gate is shared code (`egressRefusal`), so this proves the exact logic the agent enforces.
The last row also does a real webhook round trip when `echo` is registered.

It's a **heuristic** taint tracker (substring/token match, not full information-flow control),
conservative by design — it can over-flag, which fails safe.

## Data-flow firewall v3 — remembered per-destination policy

The per-call `allow_egress` / `allow_tainted_egress` flags are *one-shot* consent. v3 adds a
**persistent policy**, keyed by destination host, so a decision is remembered across calls and
a destination can be permanently blocked. Two independent axes per host — *may data reach it*
and *may LOCAL data reach it* — each `ALLOW` / `DENY` / `UNSET`:

- **`ALLOW`** short-circuits the flag — egress to that host is permitted without passing it.
- **`DENY`** overrides the flag — a hard block even if `allow_egress=true` is passed.
- **`UNSET`** (default) falls back to the per-call flag, exactly as v1/v2.

Policy lives in the `CapabilityBroker` grant store (the same package-keyed, revocable surface
as capability grants), managed via `edgelm egress` or `POST /v1/edge/egress/{op}`:

```
edgelm egress allow 127.0.0.1          # remember: this host is reachable
edgelm agent "echo the word banana"    # egress now works WITHOUT --allow-egress
edgelm egress allow-tainted 127.0.0.1  # ...and may carry local data too
edgelm egress deny 10.0.0.9            # hard-block a host (overrides the flag)
edgelm egress list                     # show all remembered policies
edgelm egress forget 127.0.0.1         # back to flag-only (UNSET)
```

Precedence is enforced in one shared `egressRefusal` gate (agent + `firewall-test` alike), so
policy and flags compose identically everywhere. This is the arch doc's "consent, remembered":
an app consents to a destination once, not every call, and the user can revoke it.

Still ahead: precise byte-provenance IFC (vs. the substring heuristic), a graphical per-app
consent UI on top of this policy store, and sandboxed *in-process* plugins. The egress policy +
per-step taint record are the seams those build on.
