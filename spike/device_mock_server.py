"""Loopback-only OpenAI-compatible fixture for Android integration checks. No keys."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        text = 'DEVICE_STREAM_OK'
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream' if data.get('stream') else 'application/json')
        self.end_headers()
        if data.get('stream'):
            for piece in ['DEVICE_', 'STREAM_', 'OK']:
                chunk = {'choices': [{'delta': {'content': piece}}]}
                self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
                self.wfile.flush()
            self.wfile.write(b'data: [DONE]\n\n')
        else:
            self.wfile.write(json.dumps({'choices': [{'message': {'content': text}}]}).encode())

if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()
