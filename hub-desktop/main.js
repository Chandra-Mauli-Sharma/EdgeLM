// EdgeLM Hub — Electron main process.
//
// The Hub's control surface is the OpenAI-compatible HTTP shim inside the runtime-service
// process on the phone (127.0.0.1:1408, DEBUG builds). A computer reaches it over adb:
//   adb forward tcp:1408 tcp:1408   → localhost:1408 on this machine hits the device shim.
//
// All HTTP happens HERE, in the main process (Node's http), not in the renderer — so there
// is no CORS/mixed-content friction, and the renderer only ever talks to us over IPC.

const { app, BrowserWindow, ipcMain, shell } = require("electron");
const http = require("http");
const { spawn, execFile } = require("child_process");
const path = require("path");

let win;
// Where the device shim is reached. Host/port are adjustable from the UI (Settings).
let target = { host: "127.0.0.1", port: 1408 };

function createWindow() {
  win = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: "#0B0E10",
    title: "EdgeLM Hub",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, "renderer", "index.html"));
  // Open external links (docs) in the system browser, never in-app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });

// ---- Core HTTP request to the Hub shim -----------------------------------------
// Resolves to { ok, status, json?, text, error? }. Never throws to the renderer.
function hubRequest({ method = "GET", path: reqPath, body }) {
  return new Promise((resolve) => {
    const payload = body != null ? Buffer.from(typeof body === "string" ? body : JSON.stringify(body)) : null;
    const req = http.request(
      {
        host: target.host,
        port: target.port,
        path: reqPath,
        method,
        headers: payload
          ? { "Content-Type": "application/json", "Content-Length": payload.length }
          : {},
        timeout: 120000,
      },
      (res) => {
        let data = "";
        res.setEncoding("utf8");
        res.on("data", (c) => (data += c));
        res.on("end", () => {
          let json = null;
          try { json = JSON.parse(data); } catch (_) {}
          resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode, json, text: data });
        });
      }
    );
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, status: 0, error: "request timed out" }); });
    req.on("error", (e) => resolve({ ok: false, status: 0, error: e.code === "ECONNREFUSED"
      ? `no runtime at ${target.host}:${target.port} — is the app running and 'adb forward tcp:${target.port} tcp:${target.port}' set up?`
      : e.message }));
    if (payload) req.write(payload);
    req.end();
  });
}

ipcMain.handle("hub:request", (_e, args) => hubRequest(args));

ipcMain.handle("hub:setTarget", (_e, t) => {
  if (t && t.host) target.host = String(t.host);
  if (t && t.port) target.port = parseInt(t.port, 10) || target.port;
  return target;
});
ipcMain.handle("hub:getTarget", () => target);

// ---- Streaming chat (SSE) → forwarded to the renderer as IPC events -------------
ipcMain.handle("hub:stream", (e, { path: reqPath, body, streamId }) => {
  return new Promise((resolve) => {
    const payload = Buffer.from(JSON.stringify(body || {}));
    const req = http.request(
      {
        host: target.host, port: target.port, path: reqPath, method: "POST",
        headers: { "Content-Type": "application/json", "Content-Length": payload.length },
        timeout: 120000,
      },
      (res) => {
        res.setEncoding("utf8");
        let buf = "";
        res.on("data", (chunk) => {
          buf += chunk;
          let idx;
          while ((idx = buf.indexOf("\n\n")) >= 0) {
            const frame = buf.slice(0, idx).trim();
            buf = buf.slice(idx + 2);
            if (!frame.startsWith("data:")) continue;
            const payloadStr = frame.slice(5).trim();
            if (payloadStr === "[DONE]") { e.sender.send("hub:stream:end", { streamId }); continue; }
            try {
              const obj = JSON.parse(payloadStr);
              const tok = obj.choices?.[0]?.delta?.content;
              if (tok) e.sender.send("hub:stream:token", { streamId, token: tok });
            } catch (_) {}
          }
        });
        res.on("end", () => { e.sender.send("hub:stream:end", { streamId }); resolve({ ok: true }); });
      }
    );
    req.on("timeout", () => { req.destroy(); e.sender.send("hub:stream:error", { streamId, error: "timed out" }); resolve({ ok: false }); });
    req.on("error", (err) => { e.sender.send("hub:stream:error", { streamId, error: err.message }); resolve({ ok: false }); });
    req.write(payload);
    req.end();
  });
});

// ---- adb helpers ----------------------------------------------------------------
function runAdb(args) {
  return new Promise((resolve) => {
    execFile("adb", args, { timeout: 15000 }, (err, stdout, stderr) => {
      if (err) resolve({ ok: false, error: (stderr || err.message || "").trim() });
      else resolve({ ok: true, out: (stdout || "").trim() });
    });
  });
}
ipcMain.handle("adb:devices", () => runAdb(["devices"]));
ipcMain.handle("adb:forward", () => runAdb(["forward", `tcp:${target.port}`, `tcp:${target.port}`]));
