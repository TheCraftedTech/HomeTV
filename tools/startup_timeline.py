"""Zeitleiste eines Sender-Starts an der FRITZ!Box (SAT>IP), rein lesend, nur Standardbibliothek.

Bildet den Ablauf der App nach (TCP-Connect, OPTIONS, DESCRIBE, SETUP, Punch, PLAY) und misst je Start:
  connect / options / describe / setup / play      Dauer der RTSP-Schritte (ms, kumulativ ab Start)
  first_rtp                                       erstes RTP-Paket nach PLAY
  first_pcr                                       erster PCR im Strom
  first_idr / second_idr                          erstes bzw. zweites H.264-IDR-Bild (Decoder kann erst ab einem IDR anzeigen)
  gop_ms                                          Abstand der IDR (Keyframe-Intervall)
  last_cc_err                                     Zeitpunkt des letzten TS-Continuity-Fehlers (relativ zum ersten RTP-Paket)
Aufruf:  python -I tools/startup_timeline.py <rtsp-url> [laeufe=6] [sekunden_je_lauf=6]
"""
import re
import socket
import statistics
import sys
import time
from urllib.parse import urlsplit


def one_run(url, seconds):
    u = urlsplit(url)
    host, port = u.hostname, u.port or 554
    t0 = time.perf_counter()
    ms = lambda: round((time.perf_counter() - t0) * 1000)
    res = {}
    s = socket.create_connection((host, port), timeout=6)
    res["connect"] = ms()
    cseq = [0]
    session = [None]

    def req(method, target, extra=""):
        cseq[0] += 1
        r = f"{method} {target} RTSP/1.0\r\nCSeq: {cseq[0]}\r\nUser-Agent: HomeTV-timeline\r\n"
        if session[0]:
            r += f"Session: {session[0]}\r\n"
        s.sendall((r + extra + "\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += s.recv(4096)
        head, _, body = buf.partition(b"\r\n\r\n")
        m = re.search(rb"(?i)content-length:\s*(\d+)", head)
        n = int(m.group(1)) if m else 0
        while len(body) < n:
            body += s.recv(4096)
        hdr = {}
        for line in head.decode(errors="replace").split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                hdr[k.strip().lower()] = v.strip()
        return hdr

    req("OPTIONS", url); res["options"] = ms()
    req("DESCRIBE", url, "Accept: application/sdp\r\n"); res["describe"] = ms()
    rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rtp.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    rtp.bind(("", 0))
    if rtp.getsockname()[1] % 2:
        rtp.close(); rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); rtp.bind(("", 0))
    p = rtp.getsockname()[1]
    h = req("SETUP", url, f"Transport: RTP/AVP;unicast;client_port={p}-{p + 1}\r\n")
    session[0] = h["session"].split(";")[0]
    res["setup"] = ms()
    sp = int(re.search(r"server_port=(\d+)", h["transport"]).group(1))
    play = f"rtsp://{host}:{port}/stream={h['com.ses.streamid']}"
    rtp.sendto(b"\xce\xfa\xed\xfe", (host, sp))
    req("PLAY", play); res["play"] = ms()

    rtp.settimeout(0.05)
    first_rtp = first_pcr = None
    idr_times, cc_last, cc_err_times = [], {}, []
    prev3 = b""
    vid_pid = None
    end = time.perf_counter() + seconds
    while time.perf_counter() < end:
        try:
            data, _ = rtp.recvfrom(65535)
        except socket.timeout:
            continue
        t = ms()
        if first_rtp is None:
            first_rtp = t
        off = 12 + (data[0] & 0x0F) * 4
        if data[0] & 0x10:
            off += 4 + 4 * ((data[off + 2] << 8) | data[off + 3])
        pl = data[off:]
        for i in range(0, len(pl) - 187, 188):
            pk = pl[i:i + 188]
            if pk[0] != 0x47:
                continue
            pid = ((pk[1] & 0x1F) << 8) | pk[2]
            afc = (pk[3] >> 4) & 3
            cc = pk[3] & 15
            if pid != 0x1FFF and afc & 1:
                if pid in cc_last and cc != ((cc_last[pid] + 1) & 15) and cc != cc_last[pid]:
                    cc_err_times.append(t)
                cc_last[pid] = cc
            if afc & 2 and pk[4] >= 7 and pk[5] & 0x10 and first_pcr is None:
                first_pcr = t
            if not afc & 1:
                continue
            o = 4 + (1 + pk[4] if afc & 2 else 0)
            body = pk[o:]
            if (pk[1] & 0x40) and body[:3] == b"\x00\x00\x01" and 0xE0 <= body[3] <= 0xEF:
                vid_pid = pid
            if pid == vid_pid:
                buf = prev3 + body
                j = 0
                while True:
                    j = buf.find(b"\x00\x00\x01", j)
                    if j < 0 or j + 3 >= len(buf):
                        break
                    if (buf[j + 3] & 0x1F) == 5 and buf[j + 3] < 0x80:   # NAL-Typ 5 = IDR-Slice
                        if not idr_times or t - idr_times[-1] > 100:
                            idr_times.append(t)
                    j += 3
                prev3 = body[-3:]
    try:
        s.settimeout(2)
        req("TEARDOWN", play)
    except Exception:
        pass
    s.close(); rtp.close()
    res["first_rtp"] = first_rtp
    res["first_pcr"] = first_pcr
    res["first_idr"] = idr_times[0] if idr_times else None
    res["second_idr"] = idr_times[1] if len(idr_times) > 1 else None
    res["gop_ms"] = (idr_times[1] - idr_times[0]) if len(idr_times) > 1 else None
    res["last_cc_err"] = (cc_err_times[-1] - first_rtp) if cc_err_times and first_rtp else None
    res["cc_errors"] = len(cc_err_times)
    return res


def main():
    url = sys.argv[1]
    runs = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    secs = float(sys.argv[3]) if len(sys.argv) > 3 else 6.0
    allr = []
    keys = ["connect", "options", "describe", "setup", "play", "first_rtp", "first_pcr", "first_idr", "second_idr", "gop_ms", "last_cc_err", "cc_errors"]
    print(" lauf " + " ".join(f"{k:>11}" for k in keys))
    for i in range(runs):
        r = one_run(url, secs)
        allr.append(r)
        print(f"{i + 1:>5} " + " ".join(f"{str(r[k]):>11}" for k in keys))
        time.sleep(1.5)
    print("\nMedian (ms ab Start des Laufs; gop_ms/last_cc_err/cc_errors absolut):")
    for k in keys:
        v = [r[k] for r in allr if r[k] is not None]
        if v:
            print(f"  {k:<12} median={statistics.median(v):>7.0f}  min={min(v):>6}  max={max(v):>6}  (n={len(v)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
