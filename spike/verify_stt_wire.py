"""Host-only wire regression: production HTTP client -> independent MIME/WAV parser.
No microphone, credentials, Android state or external provider used.
Requires an already built app and JDK 17. Run from any directory.
"""
import email.policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import wave

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'java' / 'jdk-17'))) / 'bin'
SUFFIX = '.exe' if os.name == 'nt' else ''
seen = []
errors = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        try:
            assert self.path == '/v1/audio/transcriptions', self.path
            assert not self.headers.get('Authorization'), 'unexpected credentials'
            size = int(self.headers['Content-Length'])
            assert 0 < size < 10000, size
            body = self.rfile.read(size)
            msg = BytesParser(policy=email.policy.default).parsebytes(
                ('MIME-Version: 1.0\r\nContent-Type: ' + self.headers['Content-Type']
                 + '\r\n\r\n').encode('ascii') + body)
            assert msg.is_multipart(), 'not valid multipart'
            parts = {part.get_param('name', header='content-disposition'): part
                     for part in msg.iter_parts()}
            assert set(parts) == {'model', 'language', 'file'}, set(parts)
            assert parts['model'].get_payload(decode=True) == b'fixture-stt'
            assert parts['language'].get_payload(decode=True) == b'ru'
            audio = parts['file']
            assert audio.get_filename() == 'audio.wav'
            assert audio.get_content_type() == 'audio/wav'
            data = audio.get_payload(decode=True)
            with wave.open(io.BytesIO(data), 'rb') as wav:
                assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, 16000)
                assert wav.getnframes() == 1600
                pcm = wav.readframes(1600)
                assert pcm == bytes((i * 37 + 11) & 255 for i in range(3200))
            seen.append({'path': self.path, 'wavBytes': len(data), 'frames': 1600,
                         'rate': 16000, 'channels': 1, 'pcmBytes': len(pcm)})
            reply = b'{"text":"fixture transcript"}'
            self.send_response(200)
        except Exception as exc:
            errors.append(repr(exc))
            reply = json.dumps({'error': repr(exc)}).encode('utf-8')
            self.send_response(400)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)

SOURCE = '''import ru.alexv.anyailive.voice.HttpWire;
import ru.alexv.anyailive.voice.TranscriptionBody;
public class SttWireProbe {
  public static void main(String[] args) {
    byte[] pcm = new byte[3200];
    for (int i=0; i<pcm.length; i++) pcm[i]=(byte)(i*37+11);
    String boundary="wire-regression";
    byte[] body=TranscriptionBody.build(boundary,pcm,"fixture-stt","ru");
    HttpWire.Bin result=HttpWire.post(args[0],null,
      "multipart/form-data; boundary="+boundary,body,5000);
    if (result.code != 200 || !result.text.contains("fixture transcript"))
      throw new AssertionError("HTTP "+result.code+": "+result.text);
    System.out.println("PRODUCTION_HTTP_RESPONSE_OK");
  }
}
'''

def main():
    server = HTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='anyailive-wire-') as tmp:
            source = Path(tmp) / 'SttWireProbe.java'
            source.write_text(SOURCE, encoding='utf-8')
            classes = ROOT / 'build' / 'classes'
            subprocess.run([str(JDK / ('javac' + SUFFIX)), '-encoding', 'UTF-8',
                            '-cp', str(classes), '-d', tmp, str(source)], check=True)
            subprocess.run([str(JDK / ('java' + SUFFIX)), '-cp',
                            os.pathsep.join([tmp, str(classes)]), 'SttWireProbe',
                            f'http://127.0.0.1:{server.server_port}/v1/audio/transcriptions'],
                           check=True, timeout=15)
        assert not errors, errors
        assert len(seen) == 1, seen
        print('PASS_HOST_STT_WIRE ' + json.dumps(seen[0], sort_keys=True))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)

if __name__ == '__main__':
    main()
