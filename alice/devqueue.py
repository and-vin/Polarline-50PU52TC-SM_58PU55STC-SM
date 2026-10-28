"""Local command queue for testing the TV agent without the cloud (same protocol as step 6).

  python3 alice/devqueue.py --secret <s> [--port 8787]
  curl -X POST localhost:8787/send -H 'Authorization: Bearer <s>' -d '{"app":"vk","action":"search","uri":"..."}'

TV side:  GET /tv/poll (long-poll, 204 after 25 s), POST /tv/ack.
Skill side: POST /send -> waits up to 90 s for the TV's ack and returns it.
"""

import argparse
import json
import queue
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

POLL_WAIT = 25
commands = queue.Queue()
acks = {}
acks_ready = threading.Condition()
SECRET = ""


class Handler(BaseHTTPRequestHandler):
    def _auth(self):
        if self.headers.get("Authorization") != f"Bearer {SECRET}":
            self._send(401, {"error": "unauthorized"})
            return False
        return True

    def _send(self, code, obj=None):
        body = b"" if obj is None else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        if body:
            self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self.path != "/tv/poll" or not self._auth():
            return self._send(404) if self.path != "/tv/poll" else None
        try:
            cmd = commands.get(timeout=POLL_WAIT)
        except queue.Empty:
            return self._send(204)
        self._send(200, cmd)

    def do_POST(self):
        if not self._auth():
            return
        if self.path == "/tv/ack":
            ack = self._body()
            with acks_ready:
                acks[ack.get("id")] = ack
                acks_ready.notify_all()
            return self._send(200, {"ok": True})
        if self.path == "/send":
            cmd = self._body()
            cmd["id"] = cmd.get("id") or uuid.uuid4().hex
            commands.put(cmd)
            with acks_ready:
                acks_ready.wait_for(lambda: cmd["id"] in acks, timeout=90)
                ack = acks.pop(cmd["id"], None)
            return self._send(200 if ack else 504, ack or {"id": cmd["id"], "error": "TV did not answer"})
        self._send(404)

    def log_message(self, fmt, *args):
        print(self.address_string(), fmt % args, flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--secret", required=True)
    ap.add_argument("--port", type=int, default=8787)
    a = ap.parse_args()
    SECRET = a.secret
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()
