#!/usr/bin/env python
"""Home TV - lokaler Stream-Test (nur Python-Standardbibliothek + ffplay).

Liest tests/data/tv.m3u, findet "Das Erste HD", zeigt die URL, prueft per RTSP-Handshake,
ob die FRITZ!Box den Stream bereitstellt, und startet optional ffplay.

Aufruf (aus dem Projektordner):
    .venv\\Scripts\\python.exe scripts\\test-stream.py            # nur pruefen
    .venv\\Scripts\\python.exe scripts\\test-stream.py --play     # zusaetzlich ffplay-Fenster
    .venv\\Scripts\\python.exe scripts\\test-stream.py --play --seconds 60
    .venv\\Scripts\\python.exe scripts\\test-stream.py --channel "ZDF HD"
    .venv\\Scripts\\python.exe scripts\\test-stream.py --fetch     # tv.m3u frisch von der Box laden

Hinweis: ffplay beendet die RTSP-Session sauber (TEARDOWN), wenn man das Fenster mit Q/ESC schliesst.
Das Skript beendet ffplay nach --seconds ebenfalls "freundlich", nicht hart.
"""
import argparse
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_M3U = ROOT / "tests" / "data" / "tv.m3u"
PREFERRED = ["Das Erste HD", "Das Erste", "ARD HD"]


def parse_m3u(text):
    channels, name = [], None
    for raw in text.splitlines():
        line = raw.strip().lstrip("﻿")
        if line.startswith("#EXTINF"):
            name = line.split(",", 1)[1].strip() if "," in line else ""
        elif line and not line.startswith("#"):
            if name is not None and line.startswith("rtsp://"):
                channels.append((name, line))
            name = None
    return channels


def find_channel(channels, wanted):
    names = [wanted] if wanted else PREFERRED
    for n in names:
        for cname, url in channels:
            if cname.lower() == n.lower():
                return cname, url
    return None


def rtsp_check(url, timeout=6.0):
    """OPTIONS -> DESCRIBE gegen die Box (ohne einen Tuner zu belegen). Gibt (ok, Meldung) zurueck."""
    u = urlsplit(url)
    try:
        s = socket.create_connection((u.hostname, u.port or 554), timeout=timeout)
    except OSError as e:
        return False, f"FRITZ!Box {u.hostname}:{u.port or 554} nicht erreichbar: {e}"
    try:
        s.settimeout(timeout)
        for cseq, (method, extra) in enumerate([("OPTIONS", ""), ("DESCRIBE", "Accept: application/sdp\r\n")], 1):
            s.sendall(f"{method} {url} RTSP/1.0\r\nCSeq: {cseq}\r\n{extra}\r\n".encode())
            data = b""
            while b"\r\n\r\n" not in data:
                chunk = s.recv(4096)
                if not chunk:
                    return False, "Verbindung von der Box geschlossen"
                data += chunk
            status = data.split(b"\r\n", 1)[0].decode(errors="replace")
            if " 200 " not in status:
                return False, f"{method}: {status}"
            head, _, body_bytes = data.partition(b"\r\n\r\n")
            m = re.search(rb"(?i)content-length:\s*(\d+)", head)
            need = int(m.group(1)) if m else 0
            while len(body_bytes) < need:
                chunk = s.recv(4096)
                if not chunk:
                    break
                body_bytes += chunk
            if method == "DESCRIBE":
                body = body_bytes.decode(errors="replace")
                srv = re.search(r"s=(.*)", body)
                pt = re.search(r"RTP/AVP (\d+)", body)
                return True, f"RTSP OK (Server: {srv.group(1).strip() if srv else '?'}, RTP-Payload-Typ {pt.group(1) if pt else '?'})"
    except OSError as e:
        return False, f"RTSP-Fehler: {e}"
    finally:
        s.close()
    return False, "unerwartete Antwort"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--m3u", default=str(DEFAULT_M3U), help="Pfad zur M3U (Standard: tests/data/tv.m3u)")
    ap.add_argument("--fetch", action="store_true", help="M3U vorher von der FRITZ!Box laden (tvhd.m3u + tvsd.m3u)")
    ap.add_argument("--host", default="192.168.200.1", help="FRITZ!Box-Adresse fuer --fetch")
    ap.add_argument("--channel", help='Sendername (Standard: "Das Erste HD")')
    ap.add_argument("--play", action="store_true", help="ffplay starten")
    ap.add_argument("--seconds", type=int, default=0, help="ffplay nach N Sekunden beenden (0 = offen lassen)")
    args = ap.parse_args()

    m3u = Path(args.m3u)
    if args.fetch:
        parts = []
        for i, lst in enumerate(("tvhd", "tvsd")):
            try:
                with urllib.request.urlopen(f"http://{args.host}/dvb/m3u/{lst}.m3u", timeout=8) as r:
                    t = r.read().decode("utf-8", "replace")
            except OSError as e:
                print(f"FEHLER: {lst}.m3u von {args.host} nicht ladbar: {e}")
                return 2
            parts.append(t if i == 0 else "\n".join(t.splitlines()[1:]))
        m3u.parent.mkdir(parents=True, exist_ok=True)
        m3u.write_text("\n".join(parts), encoding="utf-8")
        print(f"M3U gespeichert: {m3u}")

    if not m3u.exists():
        print(f"FEHLER: {m3u} nicht gefunden. Mit --fetch von der FRITZ!Box laden "
              f"oder die Datei manuell dort ablegen (siehe README).")
        return 2
    channels = parse_m3u(m3u.read_text(encoding="utf-8", errors="replace"))
    print(f"{len(channels)} Sender in {m3u.name}")
    found = find_channel(channels, args.channel)
    if not found:
        print(f"FEHLER: Sender {args.channel or PREFERRED} nicht in der Liste.")
        return 2
    name, url = found
    print(f"Sender: {name}\nURL   : {url}")

    ok, msg = rtsp_check(url)
    print(("OK    : " if ok else "FEHLER: ") + msg)
    if not ok:
        return 1

    if args.play:
        ffplay = shutil.which("ffplay")
        if not ffplay:
            print("FEHLER: ffplay nicht gefunden (PATH). Installiere FFmpeg oder gib ffplay.exe in den PATH.")
            return 3
        cmd = [ffplay, "-hide_banner", "-loglevel", "warning", "-rtsp_transport", "udp",
               "-window_title", f"Home TV Test - {name}", "-x", "960", "-y", "540", url]
        print("Starte ffplay (Q oder ESC im Fenster beendet sauber) ...")
        p = subprocess.Popen(cmd)
        try:
            if args.seconds > 0:
                time.sleep(args.seconds)
                # freundlich beenden: unter Windows sendet terminate() hart; ffplay ohne TEARDOWN kann
                # eine Session auf der Box zurücklassen (Timeout 60 s). Daher bevorzugt das Fenster mit Q schliessen.
                p.terminate()
            else:
                p.wait()
        except KeyboardInterrupt:
            p.terminate()
        p.wait()
        print(f"ffplay beendet (Exit {p.returncode}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
