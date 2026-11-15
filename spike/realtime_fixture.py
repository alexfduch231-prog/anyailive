"""Loopback OpenAI Realtime websocket fixture for the Live screen smoke test.

No dependencies: RFC6455 server on stdlib. Speaks just enough of the protocol
for ru.alexv.anyailive.voice.LiveEngineOpenAI: accepts session.update, then
streams response.output_audio.delta (24 kHz PCM16 base64, breathing amplitude)
with matching transcript deltas, ends the turn with response.done, repeats.
Loopback only. Run: python spike/realtime_fixture.py  [port=18765]
"""
import base64
import json
import math
import socket
import struct
import sys
import threading
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18765
RATE = 24000

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def accept_key(key):
    import hashlib
    return base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()


def read_frame(sock):
    hdr = recv_n(sock, 2)
    if hdr is None:
        return None, None
    b1, b2 = hdr
    opcode = b1 & 0x0F
    masked = b2 & 0x80
    ln = b2 & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", recv_n(sock, 2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", recv_n(sock, 8))[0]
    mask = recv_n(sock, 4) if masked else None
    data = recv_n(sock, ln) if ln else b""
    if mask and data:
        data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
    return opcode, data


def recv_n(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def send_text(sock, text):
    payload = text.encode("utf-8")
    ln = len(payload)
    if ln < 126:
        head = struct.pack(">BB", 0x81, ln)
    elif ln < 65536:
        head = struct.pack(">BBH", 0x81, 126, ln)
    else:
        head = struct.pack(">BBQ", 0x81, 127, ln)
    with lock:
        sock.sendall(head + payload)


lock = threading.Lock()


def chunk_pcm(t0, seconds, level):
    n = int(RATE * seconds)
    out = bytearray()
    for i in range(n):
        t = t0 + i / RATE
        # «Речь»: огибающая дышит, внутри лёгкая вибрация
        amp = level * (0.55 + 0.45 * math.sin(2 * math.pi * 3.1 * t)) \
            * (0.7 + 0.3 * math.sin(2 * math.pi * 0.7 * t))
        v = int(32767 * amp * math.sin(2 * math.pi * 220 * t))
        out += struct.pack("<h", max(-32768, min(32767, v)))
    return bytes(out)


def run_turn(sock, tag):
    seq = 0
    chunks = 24  # ~4.8 секунды ответа
    for c in range(chunks):
        level = 0.10 + 0.30 * abs(math.sin(c * 0.45)) + 0.15 * (c % 2)
        pcm = chunk_pcm(c * 0.2, 0.2, level)
        send_text(sock, json.dumps({
            "type": "response.output_audio.delta",
            "delta": base64.b64encode(pcm).decode(),
        }))
        if c % 4 == 0:
            send_text(sock, json.dumps({
                "type": "response.output_audio_transcript.delta",
                "delta": "волна %d " % (tag * 100 + seq),
            }))
            seq += 1
        time.sleep(0.2)
    send_text(sock, json.dumps({
        "type": "response.output_audio_transcript.done",
        "transcript": "fixture turn %d done" % tag,
    }))
    send_text(sock, json.dumps({"type": "response.done"}))


def handle(sock):
    # рукопожатие
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            return
        data += chunk
    head, _, rest = data.partition(b"\r\n\r\n")
    key = None
    for line in head.decode("latin1").split("\r\n"):
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
    resp = ("HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Accept: %s\r\n\r\n" % accept_key(key))
    sock.sendall(resp.encode())
    print("client connected", flush=True)

    turn = 0
    timer = None

    def maybe_start_turn():
        nonlocal turn
        turn += 1
        threading.Thread(target=run_turn, args=(sock, turn), daemon=True).start()

    while True:
        opcode, payload = read_frame(sock)
        if opcode is None:
            break
        if opcode == 8:  # close
            break
        if opcode != 1:
            continue
        try:
            msg = json.loads(payload.decode("utf-8"))
        except ValueError:
            continue
        t = msg.get("type", "")
        print("recv:", t, flush=True)
        if t == "session.update":
            # небольшой тишины, потом старт ответа
            threading.Timer(0.6, maybe_start_turn).start()
        elif t == "response.create":
            threading.Timer(0.2, maybe_start_turn).start()
        # input_audio_buffer.append и остальное просто глотаем
    print("client gone", flush=True)


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", PORT))
srv.listen(4)
print("realtime fixture on ws://127.0.0.1:%d" % PORT, flush=True)
while True:
    conn, addr = srv.accept()
    print("TCP accept from", addr, flush=True)
    threading.Thread(target=handle, args=(conn,), daemon=True).start()
