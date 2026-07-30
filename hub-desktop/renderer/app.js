// EdgeLM Hub — renderer logic. Talks to the Hub shim only through window.hub (see preload.js).

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? "").replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));

let connected = false;
let pollTimer = null;

// Rolling per-generation metrics, measured in the renderer during streaming (each SSE frame
// is one token). Persisted so the chart survives app restarts. Cap the history length.
const HIST_MAX = 40;
let genHistory = [];
try { genHistory = JSON.parse(localStorage.getItem("edgelm.genHistory") || "[]"); } catch (_) {}
const lastGen = () => genHistory[genHistory.length - 1] || null;

function recordGen(sample) {
  genHistory.push(sample);
  if (genHistory.length > HIST_MAX) genHistory = genHistory.slice(-HIST_MAX);
  try { localStorage.setItem("edgelm.genHistory", JSON.stringify(genHistory)); } catch (_) {}
  if ($("#tab-monitor").classList.contains("active")) refreshMonitor();
}
const avg = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0);

function toast(msg, isErr) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast show" + (isErr ? " err" : "");
  clearTimeout(t._t);
  t._t = setTimeout(() => (t.className = "toast"), 2600);
}

// ---- Connection ---------------------------------------------------------------
async function applyTarget() {
  await window.hub.setTarget({ host: $("#targetHost").value.trim(), port: $("#targetPort").value.trim() });
}

async function checkHealth() {
  const r = await window.hub.request("GET", "/health");
  const dot = $("#statusDot"), txt = $("#statusText");
  if (r.ok && r.json) {
    connected = true;
    dot.className = "dot ok";
    txt.textContent = `connected · ${(r.json.warm || []).length} warm`;
  } else {
    connected = false;
    dot.className = "dot bad";
    txt.textContent = r.error ? "offline" : "no runtime";
  }
  return r.ok ? r.json : null;
}

// ---- Tabs ---------------------------------------------------------------------
$$(".nav button").forEach((b) =>
  b.addEventListener("click", () => {
    $$(".nav button").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    const tab = b.dataset.tab;
    $$(".tab").forEach((t) => t.classList.remove("active"));
    $(`#tab-${tab}`).classList.add("active");
    if (tab === "models") loadModels();
    if (tab === "firewall") { loadEgress(); loadPerms(); }
    if (tab === "monitor") refreshMonitor();
    if (tab === "tools") loadTools();
    if (tab === "knowledge") loadCollections();
  })
);

// ---- Models -------------------------------------------------------------------
async function loadModels() {
  const body = $("#modelsBody");
  const r = await window.hub.request("GET", "/v1/edge/models");
  if (!r.ok || !r.json) { body.innerHTML = `<tr><td colspan="7" class="empty">${esc(r.error || "unavailable")}</td></tr>`; return; }
  const models = r.json.models || [];
  const installed = models.filter((m) => m.installed).length;
  $("#modelsSummary").textContent = `${models.length} in catalog · ${installed} installed`;
  if (!models.length) { body.innerHTML = `<tr><td colspan="7" class="empty">Catalog empty.</td></tr>`; return; }
  body.innerHTML = models.map((m) => {
    const state = [];
    if (m.active) state.push(`<span class="badge green">active</span>`);
    if (m.installed) state.push(`<span class="badge blue">installed</span>`);
    else state.push(`<span class="badge gray">not installed</span>`);
    if (m.pinned_version >= 0) state.push(`<span class="badge amber">pinned v${m.pinned_version}</span>`);
    const actions = [];
    if (!m.installed) actions.push(`<button class="btn sm" data-pull="${esc(m.id)}">Pull</button>`);
    else {
      if (!m.active) actions.push(`<button class="btn sm" data-activate="${esc(m.id)}">Activate</button>`);
      const pinned = m.pinned_version >= 0;
      actions.push(`<button class="btn sm ghost" data-pin="${esc(m.id)}" data-pinned="${pinned}">${pinned ? "Unpin" : "Pin"}</button>`);
    }
    return `<tr data-model-id="${esc(m.id)}">
      <td><strong>${esc(m.name)}</strong><br><span class="mono muted">${esc(m.id)}</span></td>
      <td class="mono">${esc(m.family || "—")}</td>
      <td>${esc(m.params || "—")}</td>
      <td class="mono">${m.size_mb ? m.size_mb + " MB" : "—"}</td>
      <td class="mono">${m.version ?? "—"}</td>
      <td>${state.join(" ")}</td>
      <td class="act" style="text-align:right;white-space:nowrap">${actions.join(" ")}</td>
    </tr>`;
  }).join("");
  startDownloadPoll();
}

// ---- Download progress (WorkManager-backed) -----------------------------------
let dlTimer = null;
function startDownloadPoll() { clearTimeout(dlTimer); pollDownloads(); }
async function pollDownloads() {
  const r = await window.hub.request("GET", "/v1/edge/downloads");
  const dls = (r.json && r.json.downloads) || [];
  let anyActive = false, anyDone = false;
  dls.forEach((d) => {
    const row = document.querySelector(`#modelsBody tr[data-model-id="${cssAttr(d.id)}"]`);
    const act = row && row.querySelector(".act");
    if (d.state === "RUNNING" || d.state === "ENQUEUED") {
      anyActive = true;
      if (act) {
        const pct = d.pct >= 0 ? d.pct : 0;
        const indet = d.state === "ENQUEUED" || d.pct < 0;
        const label = d.state === "ENQUEUED" ? "queued" : pct + "%";
        act.innerHTML = `<div class="dl"><div class="dl-bar${indet ? " indet" : ""}"><span style="width:${pct}%"></span></div><span class="dl-pct">${label}</span></div>`;
      }
    } else if (d.state === "SUCCEEDED") {
      anyDone = true;
    } else if (d.state === "FAILED" && act) {
      act.innerHTML = `<span class="badge red" title="${esc(d.error || "")}">failed</span>`;
    }
  });
  if (anyActive) dlTimer = setTimeout(pollDownloads, 1200);
  else if (anyDone && $("#tab-models").classList.contains("active")) loadModels();
}
const cssAttr = (s) => String(s).replace(/["\\]/g, "\\$&");

$("#modelsBody").addEventListener("click", async (e) => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.pull) {
    b.disabled = true; b.innerHTML = `<span class="spin"></span>`;
    const r = await window.hub.request("POST", "/v1/edge/pull", { model: b.dataset.pull });
    toast(r.json?.status === "enqueued" ? `Pull enqueued: ${r.json.name} (${r.json.size_mb} MB)` : (r.json?.error || "pull failed"), !r.json?.status);
    setTimeout(loadModels, 800);
  } else if (b.dataset.activate) {
    const r = await window.hub.request("POST", "/v1/edge/activate", { model: b.dataset.activate });
    toast(r.json?.active ? `Activated ${r.json.id}` : (r.json?.error || "activate failed"), !r.json?.active);
    loadModels();
  } else if (b.dataset.pin) {
    const pinned = b.dataset.pinned === "true";
    const r = await window.hub.request("POST", "/v1/edge/pin", { model: b.dataset.pin, pinned: !pinned });
    toast(r.json?.error ? r.json.error : (!pinned ? `Pinned v${r.json.pinned_version}` : "Unpinned"), !!r.json?.error);
    loadModels();
  }
});
$("#btnRefreshModels").addEventListener("click", loadModels);

// ---- Monitor ------------------------------------------------------------------
async function refreshMonitor() {
  const h = await checkHealth();
  const metrics = $("#monMetrics");
  const warm = h ? (h.warm || []) : [];
  const lg = lastGen();
  const avgToks = avg(genHistory.map((g) => g.toks));
  metrics.innerHTML = [
    ["Status", connected ? "online" : "offline"],
    ["Warm models", warm.length],
    ["Last tok/s", lg ? lg.toks : "—"],
    ["Avg tok/s", avgToks ? avgToks.toFixed(1) : "—"],
    ["Last TTFT", lg ? lg.ttft + " ms" : "—"],
    ["Generations", genHistory.length],
  ].map(([l, v]) => `<div class="metric"><div class="v">${esc(v)}</div><div class="l">${esc(l)}</div></div>`).join("");

  $("#warmList").innerHTML = warm.length
    ? warm.map((w) => `<span class="badge blue" style="margin:2px">${esc(w)}</span>`).join(" ")
    : `<span class="empty">None resident.</span>`;

  const log = $("#genLog");
  if (genHistory.length) {
    log.className = "output";
    log.style.maxHeight = "180px";
    log.innerHTML = [...genHistory].reverse().slice(0, 12).map((g) => {
      const t = new Date(g.ts).toLocaleTimeString();
      return `<div><span class="muted">${esc(t)}</span>  <span class="step">${g.toks} tok/s</span> · TTFT ${g.ttft} ms · ${g.count} tok in ${g.ms} ms <span class="muted">(${esc(g.mode)})</span></div>`;
    }).join("");
  }
  drawChart();
}

// Dependency-free sparkline of tok/s over recent generations (canvas 2d, no CDN).
function drawChart() {
  const c = $("#tpsChart"); if (!c) return;
  const ctx = c.getContext("2d");
  const W = c.width, H = c.height, pad = 22;
  ctx.clearRect(0, 0, W, H);
  const data = genHistory.map((g) => g.toks);
  $("#chartEmpty").style.display = data.length ? "none" : "";
  if (!data.length) return;
  const max = Math.max(...data, 1) * 1.15, min = 0;
  const x = (i) => pad + (i * (W - pad * 2)) / Math.max(data.length - 1, 1);
  const y = (v) => H - pad - ((v - min) / (max - min)) * (H - pad * 2);

  // Colors from the active theme's CSS variables, so the chart adapts to light/dark.
  const cs = getComputedStyle(document.documentElement);
  const cSignal = cs.getPropertyValue("--signal").trim() || "#9bff3c";
  const cVolt = cs.getPropertyValue("--volt").trim() || "#2de3ff";
  const cGrid = cs.getPropertyValue("--border").trim() || "#232b31";
  const cLabel = cs.getPropertyValue("--steel").trim() || "#84939b";

  // gridlines + y labels
  ctx.strokeStyle = cGrid; ctx.fillStyle = cLabel; ctx.font = "11px ui-monospace, monospace"; ctx.lineWidth = 1;
  for (let g = 0; g <= 3; g++) {
    const val = (max * g) / 3, yy = y(val);
    ctx.beginPath(); ctx.moveTo(pad, yy); ctx.lineTo(W - pad, yy); ctx.stroke();
    ctx.fillText(val.toFixed(0), 2, yy + 3);
  }
  // area fill under the curve (signal-green tint)
  ctx.save();
  ctx.beginPath();
  data.forEach((v, i) => (i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))));
  ctx.lineTo(x(data.length - 1), H - pad); ctx.lineTo(x(0), H - pad); ctx.closePath();
  ctx.globalAlpha = 0.16; ctx.fillStyle = cSignal; ctx.fill();
  ctx.restore();
  // line (signal green with a soft glow)
  ctx.save();
  ctx.beginPath();
  data.forEach((v, i) => (i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))));
  ctx.strokeStyle = cSignal; ctx.lineWidth = 2; ctx.shadowColor = cSignal; ctx.shadowBlur = 7; ctx.stroke();
  ctx.restore();
  // points — last one highlighted in volt cyan
  data.forEach((v, i) => {
    const last = i === data.length - 1;
    ctx.save();
    ctx.beginPath(); ctx.arc(x(i), y(v), last ? 4 : 2.4, 0, 7);
    if (last) { ctx.fillStyle = cVolt; } else { ctx.globalAlpha = 0.5; ctx.fillStyle = cSignal; }
    ctx.fill();
    ctx.restore();
    if (last) { ctx.save(); ctx.globalAlpha = 0.4; ctx.beginPath(); ctx.arc(x(i), y(v), 7, 0, 7); ctx.strokeStyle = cVolt; ctx.lineWidth = 1.5; ctx.stroke(); ctx.restore(); }
  });
}
$("#btnRefreshMon").addEventListener("click", refreshMonitor);
$("#autoPoll").addEventListener("change", setupPoll);
function setupPoll() {
  clearInterval(pollTimer);
  if ($("#autoPoll").checked) pollTimer = setInterval(() => {
    checkHealth();
    if ($("#tab-monitor").classList.contains("active")) refreshMonitor();
  }, 4000);
}

// ---- Playground ---------------------------------------------------------------
const modeSel = $("#pgMode");
modeSel.addEventListener("change", () => {
  const m = modeSel.value;
  $("#agentFlags").style.display = m === "agent" ? "" : "none";
  $("#visionPick").style.display = m === "vision" ? "" : "none";
  $("#speechPick").style.display = m === "speech" ? "" : "none";
});
// Segmented control drives the (hidden) select so the rest of the logic is unchanged.
$$("#pgModeSeg .seg-btn").forEach((b) =>
  b.addEventListener("click", () => {
    $$("#pgModeSeg .seg-btn").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    modeSel.value = b.dataset.mode;
    modeSel.dispatchEvent(new Event("change"));
  })
);

let pickedImage = null;
$("#btnPickImage").addEventListener("click", () => $("#imgFile").click());
$("#imgFile").addEventListener("change", (e) => {
  const f = e.target.files[0]; if (!f) return;
  const reader = new FileReader();
  reader.onload = () => { pickedImage = reader.result.split(",")[1]; $("#imgName").textContent = f.name; };
  reader.readAsDataURL(f);
});
let pickedAudio = null;
$("#btnPickAudio").addEventListener("click", () => $("#audioFile").click());
$("#audioFile").addEventListener("change", (e) => {
  const f = e.target.files[0]; if (!f) return;
  const reader = new FileReader();
  reader.onload = () => { pickedAudio = reader.result.split(",")[1]; $("#audioName").textContent = f.name; };
  reader.readAsDataURL(f);
});

// In-app mic capture → WAV (mtmd/miniaudio decodes wav natively; no encoder dependency).
let recState = { on: false, stream: null, ctx: null, node: null, sink: null, buffers: [], rate: 48000 };
$("#btnRecord").addEventListener("click", async () => {
  const btn = $("#btnRecord");
  if (recState.on) { finishRecording(); btn.classList.remove("recording"); btn.lastChild.textContent = "Record"; return; }
  try {
    recState.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (err) { toast("Microphone unavailable — grant mic access", true); return; }
  recState.ctx = new AudioContext();
  recState.rate = recState.ctx.sampleRate;
  recState.buffers = [];
  const src = recState.ctx.createMediaStreamSource(recState.stream);
  recState.node = recState.ctx.createScriptProcessor(4096, 1, 1);
  recState.sink = recState.ctx.createGain(); recState.sink.gain.value = 0; // silence local playback
  recState.node.onaudioprocess = (e) => recState.buffers.push(new Float32Array(e.inputBuffer.getChannelData(0)));
  src.connect(recState.node); recState.node.connect(recState.sink); recState.sink.connect(recState.ctx.destination);
  recState.on = true;
  btn.classList.add("recording"); btn.lastChild.textContent = "Stop";
  $("#audioName").textContent = "recording…";
});
function finishRecording() {
  recState.on = false;
  try { recState.node && recState.node.disconnect(); } catch (_) {}
  try { recState.stream && recState.stream.getTracks().forEach((t) => t.stop()); } catch (_) {}
  const len = recState.buffers.reduce((a, b) => a + b.length, 0);
  const pcm = new Float32Array(len); let off = 0;
  recState.buffers.forEach((b) => { pcm.set(b, off); off += b.length; });
  try { recState.ctx && recState.ctx.close(); } catch (_) {}
  if (!len) { $("#audioName").textContent = "(nothing recorded)"; return; }
  pickedAudio = bufToB64(encodeWav(pcm, recState.rate));
  $("#audioName").textContent = `recording (${(len / recState.rate).toFixed(1)}s)`;
}
function encodeWav(samples, rate) {
  const buf = new ArrayBuffer(44 + samples.length * 2), view = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); };
  str(0, "RIFF"); view.setUint32(4, 36 + samples.length * 2, true); str(8, "WAVE");
  str(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  str(36, "data"); view.setUint32(40, samples.length * 2, true);
  let o = 44; for (let i = 0; i < samples.length; i++) { const s = Math.max(-1, Math.min(1, samples[i])); view.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true); o += 2; }
  return buf;
}
function bufToB64(buf) { let bin = ""; const b = new Uint8Array(buf); for (let i = 0; i < b.length; i++) bin += String.fromCharCode(b[i]); return btoa(bin); }

$("#btnSend").addEventListener("click", runPlayground);
$("#pgPrompt").addEventListener("keydown", (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) runPlayground(); });

async function runPlayground() {
  const out = $("#pgOutput");
  const prompt = $("#pgPrompt").value.trim();
  const mode = modeSel.value;
  if ((mode === "chat" || mode === "agent") && !prompt) { toast("Enter a prompt", true); return; }
  const btn = $("#btnSend"); btn.disabled = true;
  out.textContent = "";

  try {
    if (mode === "chat") {
      const id = "s" + Date.now();
      const t0 = performance.now(); let ttft = null, count = 0;
      await window.hub.stream("/v1/chat/completions",
        { model: "default", stream: true, messages: [{ role: "user", content: prompt }] }, id,
        { onToken: (t) => { if (ttft === null) ttft = performance.now() - t0; count++; out.textContent += t; out.scrollTop = out.scrollHeight; },
          onEnd: () => {
            if (!out.textContent) out.innerHTML = `<span class="empty">(no output)</span>`;
            const ms = Math.round(performance.now() - t0);
            if (count > 0) recordGen({ ts: Date.now(), mode: "chat", count, ms, ttft: Math.round(ttft || 0), toks: +(count / (ms / 1000)).toFixed(1) });
            btn.disabled = false;
          },
          onError: (err) => { out.innerHTML = `<span class="refused">${esc(err)}</span>`; btn.disabled = false; } });
      return; // streaming re-enables the button
    }
    if (mode === "agent") {
      out.innerHTML = `<span class="spin"></span> running agent…`;
      const r = await window.hub.request("POST", "/v1/edge/agent", {
        prompt,
        allow_side_effects: $("#flagSide").checked,
        allow_egress: $("#flagEgress").checked,
        allow_tainted_egress: $("#flagTaint").checked,
      });
      out.innerHTML = renderAgent(r.json) || `<span class="empty">${esc(r.error || "no response")}</span>`;
    }
    if (mode === "vision") {
      if (!pickedImage) { toast("Choose an image first", true); btn.disabled = false; return; }
      out.innerHTML = `<span class="spin"></span> captioning…`;
      const r = await window.hub.request("POST", "/v1/edge/caption",
        { image: pickedImage, prompt: prompt || "Describe this image in detail." });
      out.textContent = r.json?.caption || r.json?.error || r.error || "(no caption)";
    }
    if (mode === "speech") {
      if (!pickedAudio) { toast("Choose an audio file first", true); btn.disabled = false; return; }
      out.innerHTML = `<span class="spin"></span> transcribing…`;
      const r = await window.hub.request("POST", "/v1/edge/transcribe",
        { audio: pickedAudio, prompt: prompt || "Transcribe the audio verbatim." });
      out.textContent = r.json?.text || r.json?.error || r.error || "(no text)";
    }
  } catch (err) {
    out.innerHTML = `<span class="refused">${esc(err.message || err)}</span>`;
  }
  btn.disabled = false;
}

function renderAgent(j) {
  if (!j) return "";
  let html = "";
  for (const s of j.steps || []) {
    const refused = String(s.result).startsWith("refused");
    const cls = refused ? "refused" : "step";
    const egress = s.egress ? ` <span class="muted">(egress → ${esc(s.egress)})</span>` : "";
    const taint = s.tainted_egress ? ` <span class="tainted">[TAINTED]</span>` : "";
    html += `<div class="${cls}">▸ ${esc(s.tool)} → ${esc(s.result)}${egress}${taint}</div>`;
  }
  html += `\n<div><strong>answer:</strong> ${esc(j.answer || "")}</div>`;
  return html;
}

// ---- Firewall: egress ---------------------------------------------------------
async function loadEgress() {
  const body = $("#egBody");
  const r = await window.hub.request("GET", "/v1/edge/egress");
  const pols = r.json?.policies || [];
  if (!pols.length) { body.innerHTML = `<tr><td colspan="4" class="empty">No policies set.</td></tr>`; return; }
  body.innerHTML = pols.map((p) => `<tr>
    <td class="mono">${esc(p.host)}</td>
    <td>${egBadge(p.egress)}</td>
    <td>${egBadge(p.tainted)}</td>
    <td style="text-align:right"><button class="btn sm ghost danger" data-forget="${esc(p.host)}">Forget</button></td>
  </tr>`).join("");
}
function egBadge(v) {
  if (v === "allow") return `<span class="badge green">allow</span>`;
  if (v === "deny") return `<span class="badge red">deny</span>`;
  return `<span class="badge gray">unset</span>`;
}
$$("[data-eg]").forEach((b) => b.addEventListener("click", async () => {
  const host = $("#egHost").value.trim();
  if (!host) { toast("Enter a host", true); return; }
  const op = b.dataset.eg;
  const r = await window.hub.request("POST", `/v1/edge/egress/${op}`, { host });
  toast(r.json?.error ? r.json.error : `${op} ${host}`, !!r.json?.error);
  loadEgress();
}));
$("#egBody").addEventListener("click", async (e) => {
  const b = e.target.closest("button[data-forget]"); if (!b) return;
  await window.hub.request("POST", "/v1/edge/egress/forget", { host: b.dataset.forget });
  loadEgress();
});

// ---- Firewall: self-test ------------------------------------------------------
$("#btnSelfTest").addEventListener("click", async () => {
  const out = $("#selfTestOut");
  out.innerHTML = `<span class="spin"></span> running…`;
  const cases = [
    ["no consent", { allow_egress: false, allow_tainted_egress: false }],
    ["egress only", { allow_egress: true, allow_tainted_egress: false }],
    ["egress + tainted", { allow_egress: true, allow_tainted_egress: true }],
  ];
  let html = "";
  for (const [label, flags] of cases) {
    const r = await window.hub.request("POST", "/v1/edge/agent",
      { firewall_test: true, data: "banana", tool: "echo", ...flags });
    const j = r.json || {};
    const cls = j.decision === "ALLOWED" ? "step" : "refused";
    const detail = j.reason || (j.result ? `${j.result}  (egress → ${j.egress || ""})` : "");
    html += `<div class="${cls}">[${esc(label.padEnd(16))}] ${esc(j.decision || "?")}: ${esc(detail)}</div>`;
  }
  out.innerHTML = html;
});

// ---- Firewall: permissions ----------------------------------------------------
async function loadPerms() {
  const body = $("#permBody");
  const r = await window.hub.request("GET", "/v1/edge/permissions");
  const grants = r.json?.grants || [];
  if (!grants.length) { body.innerHTML = `<tr><td colspan="5" class="empty">No grants recorded.</td></tr>`; return; }
  body.innerHTML = grants.map((g) => `<tr>
    <td><strong>${esc(g.label || g.package)}</strong><br><span class="mono muted">${esc(g.package)}</span></td>
    <td class="mono">${esc(g.capability)}</td>
    <td>${g.risk === "high" ? `<span class="badge amber">high</span>` : `<span class="badge gray">low</span>`}</td>
    <td>${g.granted ? `<span class="badge green">granted</span>` : `<span class="badge red">denied</span>`}</td>
    <td style="text-align:right;white-space:nowrap">
      <button class="btn sm ghost" data-perm="${g.granted ? "deny" : "grant"}" data-pkg="${esc(g.package)}" data-cap="${esc(g.capability)}">${g.granted ? "Deny" : "Grant"}</button>
      <button class="btn sm ghost danger" data-perm="revoke" data-pkg="${esc(g.package)}" data-cap="${esc(g.capability)}">Revoke</button>
    </td>
  </tr>`).join("");
}
$("#permBody").addEventListener("click", async (e) => {
  const b = e.target.closest("button[data-perm]"); if (!b) return;
  const r = await window.hub.request("POST", `/v1/edge/permissions/${b.dataset.perm}`,
    { package: b.dataset.pkg, capability: b.dataset.cap });
  toast(r.json?.error ? r.json.error : `${b.dataset.perm} ${b.dataset.cap}`, !!r.json?.error);
  loadPerms();
});
$("#btnRefreshPerms").addEventListener("click", loadPerms);

// ---- Tools (app-registered webhooks) -----------------------------------------
async function loadTools() {
  const body = $("#toolsBody");
  const r = await window.hub.request("GET", "/v1/edge/tools");
  const tools = r.json?.tools || [];
  $("#toolsSummary").textContent = tools.length ? `${tools.length} registered` : "";
  if (!tools.length) { body.innerHTML = `<tr><td colspan="4" class="empty">No tools registered.</td></tr>`; return; }
  body.innerHTML = tools.map((t) => {
    let host = t.url; try { host = new URL(t.url).host; } catch (_) {}
    return `<tr>
      <td><strong>${esc(t.name)}</strong></td>
      <td class="mono">${esc(t.url)}</td>
      <td class="muted">${esc(t.description || "—")}</td>
      <td style="text-align:right;white-space:nowrap">
        <button class="btn sm ghost" data-allow-tool="${esc(t.url)}" title="Allow egress to ${esc(host)}">Allow egress</button>
        <button class="btn sm ghost danger" data-unreg="${esc(t.name)}">Remove</button>
      </td>
    </tr>`;
  }).join("");
}
$("#btnRegisterTool").addEventListener("click", async () => {
  const name = $("#toolName").value.trim();
  const url = $("#toolUrl").value.trim();
  const description = $("#toolDesc").value.trim();
  if (!name || !url) { toast("Name and URL are required", true); return; }
  const r = await window.hub.request("POST", "/v1/edge/tools/register", { name, url, description });
  if (r.json?.registered) {
    toast(`Registered ${r.json.registered}`);
    $("#toolName").value = $("#toolUrl").value = $("#toolDesc").value = "";
    loadTools();
  } else toast(r.json?.error || r.error || "register failed", true);
});
$("#toolsBody").addEventListener("click", async (e) => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.unreg) {
    await window.hub.request("POST", "/v1/edge/tools/unregister", { name: b.dataset.unreg });
    toast(`Removed ${b.dataset.unreg}`); loadTools();
  } else if (b.dataset.allowTool) {
    const r = await window.hub.request("POST", "/v1/edge/egress/allow", { host: b.dataset.allowTool });
    toast(r.json?.host ? `Egress allowed for ${r.json.host}` : (r.json?.error || "failed"), !r.json?.host);
  }
});
$("#btnRefreshTools").addEventListener("click", loadTools);

// ---- Knowledge (embeddings + vector store + RAG) -----------------------------
async function loadCollections() {
  const el = $("#colsList");
  const r = await window.hub.request("POST", "/v1/edge/vectors/collections", {});
  const cols = r.json?.collections || [];
  if (r.json?.error) { el.className = "empty"; el.textContent = r.json.error; return; }
  if (!cols.length) { el.className = "empty"; el.textContent = "No collections yet — add documents to create one."; return; }
  el.className = "";
  el.innerHTML = cols.map((c) =>
    `<div class="row" style="margin-bottom:8px;justify-content:space-between">
      <span><span class="badge blue">${esc(c.name)}</span> <span class="muted">${c.count} doc${c.count === 1 ? "" : "s"}</span></span>
      <button class="btn sm ghost" data-usecol="${esc(c.name)}">Use</button>
    </div>`).join("");
}
$("#colsList").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-usecol]"); if (!b) return;
  $("#kCol").value = b.dataset.usecol; $("#kQueryCol").value = b.dataset.usecol;
  toast(`Using '${b.dataset.usecol}'`);
});
$("#btnAddDocs").addEventListener("click", async () => {
  const collection = $("#kCol").value.trim() || "default";
  const lines = $("#kDocs").value.split("\n").map((s) => s.trim()).filter(Boolean);
  if (!lines.length) { toast("Enter some text first", true); return; }
  const btn = $("#btnAddDocs"); btn.disabled = true; btn.innerHTML = `<span class="spin"></span>`;
  const items = lines.map((text) => ({ text }));
  const r = await window.hub.request("POST", "/v1/edge/vectors/upsert", { collection, items });
  btn.disabled = false; btn.textContent = "Add & embed";
  if (r.json?.upserted != null) { toast(`Embedded ${r.json.upserted} doc(s) into '${collection}'`); $("#kDocs").value = ""; loadCollections(); }
  else toast(r.json?.error || r.error || "add failed", true);
});
$("#btnRag").addEventListener("click", () => runKnowledge("rag"));
$("#btnVecSearch").addEventListener("click", () => runKnowledge("search"));
$("#kQuery").addEventListener("keydown", (e) => { if (e.key === "Enter") runKnowledge("rag"); });

async function runKnowledge(kind) {
  const out = $("#kOutput");
  const collection = $("#kQueryCol").value.trim() || "default";
  const query = $("#kQuery").value.trim();
  const top_k = parseInt($("#kTopK").value, 10) || 4;
  if (!query) { toast("Enter a question", true); return; }
  out.innerHTML = `<span class="spin"></span> ${kind === "rag" ? "retrieving + answering" : "searching"}…`;
  if (kind === "rag") {
    const r = await window.hub.request("POST", "/v1/edge/rag", { collection, query, top_k });
    if (!r.json || r.json.error) { out.innerHTML = `<span class="refused">${esc(r.json?.error || r.error || "failed")}</span>`; return; }
    const sources = (r.json.sources || []).map((s) =>
      `<div class="muted">  • [${s.score.toFixed(3)}] ${esc(s.text)}</div>`).join("");
    out.innerHTML = `<div><strong>answer:</strong> ${esc(r.json.answer || "")}</div>` +
      (sources ? `\n<div class="step">sources:</div>${sources}` : "");
  } else {
    const r = await window.hub.request("POST", "/v1/edge/vectors/query", { collection, query, top_k });
    if (!r.json || r.json.error) { out.innerHTML = `<span class="refused">${esc(r.json?.error || r.error || "failed")}</span>`; return; }
    const matches = r.json.matches || [];
    out.innerHTML = matches.length
      ? matches.map((m, i) => `<div><span class="step">${i + 1}.</span> [${m.score.toFixed(3)}] ${esc(m.text)} <span class="muted mono">${esc(m.id)}</span></div>`).join("")
      : `<span class="empty">No matches in '${esc(collection)}'.</span>`;
  }
}

// ---- Theme (light / dark) -----------------------------------------------------
const SUN = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>`;
const MOON = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/></svg>`;
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  $("#btnTheme").innerHTML = t === "light" ? MOON : SUN;
  if ($("#tab-monitor").classList.contains("active")) drawChart();
}
$("#btnTheme").addEventListener("click", () => {
  const next = document.documentElement.dataset.theme === "light" ? "dark" : "light";
  try { localStorage.setItem("edgelm.theme", next); } catch (_) {}
  applyTheme(next);
});
applyTheme((() => { try { return localStorage.getItem("edgelm.theme") || "dark"; } catch (_) { return "dark"; } })());

// ---- Top bar controls ---------------------------------------------------------
$("#btnReconnect").addEventListener("click", async () => { await applyTarget(); await bootstrap(); });
$("#btnForward").addEventListener("click", async () => {
  await applyTarget();
  const dev = await window.hub.adbDevices();
  const fwd = await window.hub.adbForward();
  if (fwd.ok) { toast("adb forward set"); setTimeout(bootstrap, 400); }
  else toast(fwd.error || "adb not found — is it on your PATH?", true);
});

// ---- Boot ---------------------------------------------------------------------
async function bootstrap() {
  await applyTarget();
  const h = await checkHealth();
  if (connected) loadModels();
  setupPoll();
}
bootstrap();
