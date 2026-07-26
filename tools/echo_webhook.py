#!/usr/bin/env python3
"""
A trivial webhook tool server for testing the EdgeLM agent's external-tool broker.

The runtime POSTs {"arguments": {...}} to a registered tool's URL; this server replies
{"result": "..."} — here it just echoes the arguments back. Swap the body of `handle`
for a real tool (weather, search, etc.).

Run:   python tools/echo_webhook.py            # listens on 0.0.0.0:9000
Then register it with the host address the DEVICE can reach:
  - Android emulator: http://10.0.2.2:9000/echo
  - Physical device (same Wi-Fi): http://<your-PC-LAN-IP>:9000/echo   (e.g. 192.168.1.42)
    (find it with `ipconfig`; make sure your firewall allows inbound 9000)

  edgelm tools register echo http://<addr>:9000/echo "echo the input back"
  # then, with consent:
  curl .../v1/edge/agent  -d '{"prompt":"echo the word banana","allow_side_effects":true}'
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        args = body.get("arguments", {})
        print("tool called with:", args)
        result = {"result": f"echo: {json.dumps(args)}"}
        payload = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):  # quieter
        pass


if __name__ == "__main__":
    print("echo webhook on http://0.0.0.0:9000/echo  (Ctrl+C to stop)")
    HTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
