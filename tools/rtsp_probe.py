"""Raw RTSP dialog probe for the FRITZ!Box SAT>IP server (stdlib only).

Usage: python -I tools/rtsp_probe.py "<rtsp-url>" [udp|tcp] [seconds]

Prints each request/response (OPTIONS, DESCRIBE, SETUP, PLAY), receives RTP for
a few seconds and reports payload type / packet counts, then sends TEARDOWN.
Read-only: it only tunes a tuner for the duration of the test.
"""
import socket
import sys
import time
from urllib.parse import urlsplit

url = sys.argv[1]
transport_mode = sys.argv[2] if len(sys.argv) > 2 else "udp"
seconds = float(sys.argv[3]) if len(sys.argv) > 3 else 5.0
u = urlsplit(url)
host, port = u.hostname, u.port or 554

sock = socket.create_connection((host, port), timeout=8)
cseq = 0
session = None


def request(method, target, extra=""):
    global cseq
    cseq += 1
    req = f"{method} {target} RTSP/1.0\r\nCSeq: {cseq}\r\nUser-Agent: HomeTV-probe\r\n"
    if session:
        req += f"Session: {session}\r\n"
    req += extra + "\r\n"
    print(f">>> {method} {target[:90]}{'...' if len(target) > 90 else ''}")
    if extra.strip():
        print("    " + extra.strip().replace("\r\n", "\n    "))
    sock.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    head, _, body = buf.partition(b"\r\n\r\n")
    headers = head.decode(errors="replace")
    length = 0
    for line in headers.split("\r\n"):
        if line.lower().startswith("content-length:"):
            length = int(line.split(":", 1)[1])
    while len(body) < length:
        body += sock.recv(4096)
    print("<<< " + headers.replace("\r\n", "\n    "))
    if body:
        print("    --- body ---\n    " + body.decode(errors="replace").replace("\r\n", "\n    ").replace("\n", "\n    ").rstrip())
    print()
    return headers, body.decode(errors="replace")


request("OPTIONS", url)
request("DESCRIBE", url, "Accept: application/sdp\r\n")

rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
rtp.bind(("", 0))
rtp_port = rtp.getsockname()[1]
if rtp_port % 2:
    rtp.close()
    rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rtp.bind(("", 0))
    rtp_port = rtp.getsockname()[1]
rtp.settimeout(2)

if transport_mode == "tcp":
    tr = "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
else:
    tr = f"Transport: RTP/AVP;unicast;client_port={rtp_port}-{rtp_port + 1}\r\n"
h, _ = request("SETUP", url, tr)
if not h.startswith("RTSP/1.0 200"):
    print("SETUP failed; aborting.")
    sys.exit(1)
for line in h.split("\r\n"):
    if line.lower().startswith("session:"):
        session = line.split(":", 1)[1].split(";")[0].strip()
    if line.lower().startswith("transport:"):
        server_ports = line
stream_id = None
for line in h.split("\r\n"):
    if line.lower().startswith("com.ses.streamid:"):
        stream_id = line.split(":", 1)[1].strip()
play_target = f"rtsp://{host}:{port}/stream={stream_id}" if stream_id else url
t0 = time.time()
request("PLAY", play_target)

if transport_mode == "udp":
    count, size, first = 0, 0, None
    pts = set()
    end = time.time() + seconds
    while time.time() < end:
        try:
            data, _ = rtp.recvfrom(65535)
        except socket.timeout:
            continue
        if first is None:
            first = time.time() - t0
        count += 1
        size += len(data)
        pts.add(data[1] & 0x7F)
    print(f"RTP: {count} Pakete, {size/1024:.0f} KiB in {seconds}s -> {size*8/seconds/1e6:.2f} Mbit/s, "
          f"Payload-Typen={sorted(pts)}, erstes Paket nach {first if first is None else round(first, 3)}s")
request("TEARDOWN", play_target)
sock.close()
