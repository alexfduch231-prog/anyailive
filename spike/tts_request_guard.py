"""Loopback request guard for the Android TTS branch probe. Never accepts audio."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Lock
counts = {}
lock = Lock()
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/counts':
            self.send_error(404); return
        with lock: body = json.dumps(counts).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        with lock: counts[self.path] = counts.get(self.path, 0) + 1
        # Do not read or retain microphone data or request bodies.
        self.send_error(503, 'Unexpected network request in local TTS probe')
if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()
