// Bridges the renderer (sandboxed, no Node) to the main process over a tiny, explicit API.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("hub", {
  // One-shot HTTP to the Hub shim. Returns { ok, status, json, text, error }.
  request: (method, path, body) => ipcRenderer.invoke("hub:request", { method, path, body }),

  // Streaming chat. Registers token/end/error callbacks for a given streamId, then starts it.
  stream: (path, body, streamId, { onToken, onEnd, onError }) => {
    const tok = (_e, m) => { if (m.streamId === streamId) onToken(m.token); };
    const end = (_e, m) => { if (m.streamId === streamId) { cleanup(); onEnd(); } };
    const err = (_e, m) => { if (m.streamId === streamId) { cleanup(); onError(m.error); } };
    function cleanup() {
      ipcRenderer.removeListener("hub:stream:token", tok);
      ipcRenderer.removeListener("hub:stream:end", end);
      ipcRenderer.removeListener("hub:stream:error", err);
    }
    ipcRenderer.on("hub:stream:token", tok);
    ipcRenderer.on("hub:stream:end", end);
    ipcRenderer.on("hub:stream:error", err);
    return ipcRenderer.invoke("hub:stream", { path, body, streamId });
  },

  getTarget: () => ipcRenderer.invoke("hub:getTarget"),
  setTarget: (t) => ipcRenderer.invoke("hub:setTarget", t),
  adbDevices: () => ipcRenderer.invoke("adb:devices"),
  adbForward: () => ipcRenderer.invoke("adb:forward"),
});
