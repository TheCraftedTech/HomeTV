"""UDP-/RTP-/MPEG-TS-Messung gegen die FRITZ!Box (SAT>IP). Nur Standardbibliothek, rein lesend.

Baut eine Session wie die App auf (OPTIONS, DESCRIBE, SETUP, Punch, PLAY, Keep-Alive, TEARDOWN) und misst:
  - RTP: Verluste (Sequenzluecken, Burstlaengen), Reordering, Duplikate, Empfangsrate je Sekunde
  - Ankunftsabstaende (p50/p95/p99/max), Luecken > 50/100/200 ms
  - MPEG-TS: Sync-Bytes, Continuity-Counter-Fehler je PID (= wirklich fehlende TS-Pakete)
  - PCR-Jitter: Ankunftszeit gegen PCR-Zeit. Der Versatz relativ zum Minimum ist die Verzoegerung, die ein
    Jitter-Puffer abfangen muss (p50/p95/p99/max) -> direkte Grundlage fuer die Puffergroesse.
  - Taktdrift Box <-> Client (ppm) aus der PCR-Regression

Aufruf:  python -I tools/udp_analyze.py <rtsp-url> <sekunden> [--json out.json] [--rcvbuf BYTES] [--dump roh.ts]
         (--dump schreibt den Roh-TS-Strom in Ankunftsreihenfolge, z. B. fuer tools/ts_sync_check.py)
"""
import json
import re
import socket
import statistics
import sys
import time
from collections import Counter, defaultdict
from urllib.parse import urlsplit


def pct(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    i = min(len(sorted_vals) - 1, int(round(p / 100.0 * (len(sorted_vals) - 1))))
    return sorted_vals[i]


class Rtsp:
    def __init__(self, url):
        u = urlsplit(url)
        self.url, self.host, self.port = url, u.hostname, u.port or 554
        self.sock = socket.create_connection((self.host, self.port), timeout=6)
        self.cseq, self.session, self.timeout = 0, None, 60

    def req(self, method, target, extra=""):
        self.cseq += 1
        r = f"{method} {target} RTSP/1.0\r\nCSeq: {self.cseq}\r\nUser-Agent: HomeTV-analyze\r\n"
        if self.session:
            r += f"Session: {self.session}\r\n"
        self.sock.sendall((r + extra + "\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            c = self.sock.recv(4096)
            if not c:
                raise IOError("RTSP-Verbindung geschlossen")
            buf += c
        head, _, body = buf.partition(b"\r\n\r\n")
        text = head.decode(errors="replace")
        m = re.search(r"(?i)content-length:\s*(\d+)", text)
        n = int(m.group(1)) if m else 0
        while len(body) < n:
            body += self.sock.recv(4096)
        hdr = {}
        for line in text.split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                hdr[k.strip().lower()] = v.strip()
        return int(text.split()[1]), hdr


def analyze(url, seconds, rcvbuf=4 * 1024 * 1024, dump_path=None):
    dump = open(dump_path, "wb") if dump_path else None
    r = Rtsp(url)
    st, _ = r.req("OPTIONS", url)
    st, _ = r.req("DESCRIBE", url, "Accept: application/sdp\r\n")
    rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rtp.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
    rtp.bind(("", 0))
    if rtp.getsockname()[1] % 2:
        rtp.close()
        rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rtp.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
        rtp.bind(("", 0))
    port = rtp.getsockname()[1]
    actual_rcvbuf = rtp.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
    st, h = r.req("SETUP", url, f"Transport: RTP/AVP;unicast;client_port={port}-{port + 1}\r\n")
    if st != 200:
        raise IOError(f"SETUP fehlgeschlagen: {st}")
    r.session = h["session"].split(";")[0]
    m = re.search(r"timeout=(\d+)", h["session"])
    r.timeout = int(m.group(1)) if m else 60
    sp = int(re.search(r"server_port=(\d+)", h["transport"]).group(1))
    sid = h.get("com.ses.streamid")
    play = f"rtsp://{r.host}:{r.port}/stream={sid}" if sid else url
    punch = (r.host, sp)
    rtp.sendto(b"\xce\xfa\xed\xfe", punch)
    st, _ = r.req("PLAY", play)
    if st != 200:
        raise IOError(f"PLAY fehlgeschlagen: {st}")

    rtp.settimeout(0.25)
    t_start = time.perf_counter()
    last_ka = last_punch = t_start
    last_tuner = t_start + 3  # erste Tuner-Abfrage nach ~13 s
    tuner_log = []
    arrivals = []  # (t, seq, nbytes)
    pcr_pid_count = Counter()
    pcr_samples = defaultdict(list)  # pid -> [(t, pcr27)]
    cc_last, cc_err, cc_pkts = {}, Counter(), 0
    bad_sync = 0
    try:
        while True:
            now = time.perf_counter()
            if now - t_start >= seconds:
                break
            if now - last_punch >= 5:
                rtp.sendto(b"\xce\xfa\xed\xfe", punch)
                last_punch = now
            if now - last_ka >= r.timeout / 2:
                r.req("OPTIONS", play)
                last_ka = now
            if now - last_tuner >= 10:
                last_tuner = now
                try:  # SAT>IP: DESCRIBE auf die Stream-URL liefert Pegel/Lock/Qualitaet des Tuners
                    r.sock.sendall(f"DESCRIBE {play} RTSP/1.0\r\nCSeq: {r.cseq + 1}\r\nSession: {r.session}\r\nAccept: application/sdp\r\n\r\n".encode())
                    r.cseq += 1
                    buf = b""
                    r.sock.settimeout(2)
                    while b"\r\n\r\n" not in buf:
                        buf += r.sock.recv(4096)
                    head, _, body = buf.partition(b"\r\n\r\n")
                    m = re.search(rb"(?i)content-length:\s*(\d+)", head)
                    need = int(m.group(1)) if m else 0
                    while len(body) < need:
                        body += r.sock.recv(4096)
                    mm = re.search(rb"tuner=(\d+),(\d+),(\d+),(\d+),([0-9.]+)", body)
                    if mm:
                        tuner_log.append({"t": round(now - t_start, 1), "level": int(mm.group(2)), "lock": int(mm.group(3)),
                                          "quality": int(mm.group(4)), "freq": mm.group(5).decode()})
                except Exception:
                    pass
                r.sock.settimeout(6)
            try:
                data, _ = rtp.recvfrom(65535)
            except socket.timeout:
                continue
            t = time.perf_counter()
            if len(data) < 12 or (data[0] >> 6) != 2:
                continue
            off = 12 + (data[0] & 0x0F) * 4
            if data[0] & 0x10:
                off += 4 + 4 * ((data[off + 2] << 8) | data[off + 3])
            seq = (data[2] << 8) | data[3]
            payload = data[off:]
            if dump:
                dump.write(payload)
            arrivals.append((t, seq, len(payload)))
            for i in range(0, len(payload) - 187, 188):
                p = payload[i:i + 188]
                if p[0] != 0x47:
                    bad_sync += 1
                    continue
                pid = ((p[1] & 0x1F) << 8) | p[2]
                afc = (p[3] >> 4) & 3
                cc = p[3] & 0x0F
                if pid == 0x1FFF:
                    continue
                if afc & 1:  # hat Payload -> CC zaehlt
                    cc_pkts += 1
                    if pid in cc_last:
                        exp = (cc_last[pid] + 1) & 0x0F
                        if cc != exp and cc != cc_last[pid]:  # gleiche CC = erlaubtes Duplikat
                            cc_err[pid] += 1
                    cc_last[pid] = cc
                if afc & 2 and p[4] >= 7 and (p[5] & 0x10):  # PCR vorhanden
                    base = (p[6] << 25) | (p[7] << 17) | (p[8] << 9) | (p[9] << 1) | (p[10] >> 7)
                    ext = ((p[10] & 1) << 8) | p[11]
                    pcr_samples[pid].append((t, base * 300 + ext))
                    pcr_pid_count[pid] += 1
    finally:
        try:
            r.sock.settimeout(2)
            r.req("TEARDOWN", play)
        except Exception as e:
            print("TEARDOWN-Fehler:", e)
        r.sock.close()
        rtp.close()
        if dump:
            dump.close()

    res = {"url": url, "seconds": seconds, "rcvbuf_requested": rcvbuf, "rcvbuf_actual": actual_rcvbuf}
    n = len(arrivals)
    res["packets"] = n
    res["bytes"] = sum(a[2] for a in arrivals)
    dur = arrivals[-1][0] - arrivals[0][0] if n > 1 else 0
    res["mbit_s"] = round(res["bytes"] * 8 / dur / 1e6, 3) if dur else 0
    # Sequenzanalyse
    seen = set()
    lost, dup, reorder, max_disp, bursts = 0, 0, 0, 0, Counter()
    expect = arrivals[0][1] if n else 0
    loss_times = []
    highest = expect - 1
    ext = []  # extended seq
    prev, cyc = None, 0
    for t, s, _ in arrivals:
        if prev is not None and s < prev and prev - s > 30000:
            cyc += 65536
        prev = s
        ext.append((t, s + cyc))
    exp = ext[0][1]
    for t, e in ext:
        if e in seen:
            dup += 1
            continue
        if e > exp:
            gap = e - exp
            lost += gap
            bursts[gap] += 1
            loss_times.append(round(t - t_start, 2))
            exp = e + 1
        elif e == exp:
            exp += 1
        else:  # e < exp : kam zu spaet -> war als Verlust gezaehlt, ist aber Reordering
            reorder += 1
            lost -= 1
            max_disp = max(max_disp, exp - 1 - e)
        seen.add(e)
    if tuner_log:
        res["tuner_status"] = {"samples": len(tuner_log), "level": sorted({x["level"] for x in tuner_log}),
                               "lock": sorted({x["lock"] for x in tuner_log}),
                               "quality": sorted({x["quality"] for x in tuner_log}), "freq_mhz": tuner_log[0]["freq"]}
    res.update(lost_packets=lost, duplicates=dup, reordered=reorder, max_reorder_distance=max_disp,
               loss_percent=round(100.0 * lost / max(1, n + lost), 4),
               loss_burst_sizes=dict(sorted(bursts.items())), loss_event_times_s=loss_times[:50])
    # Ankunftsabstaende
    gaps = sorted((b[0] - a[0]) * 1000 for a, b in zip(arrivals, arrivals[1:]))
    res["interarrival_ms"] = {"mean": round(statistics.mean(gaps), 3), "p50": round(pct(gaps, 50), 3),
                              "p95": round(pct(gaps, 95), 3), "p99": round(pct(gaps, 99), 3),
                              "max": round(gaps[-1], 3), "gt50ms": sum(g > 50 for g in gaps),
                              "gt100ms": sum(g > 100 for g in gaps), "gt200ms": sum(g > 200 for g in gaps)}
    # Rate je Sekunde
    per = Counter()
    for t, _, b in arrivals:
        per[int(t - t_start)] += b
    rates = [per[i] * 8 / 1e6 for i in range(int(seconds) - 1)]
    res["rate_mbit_per_s"] = {"min": round(min(rates), 2), "max": round(max(rates), 2),
                              "stdev": round(statistics.pstdev(rates), 3)} if rates else {}
    res["ts"] = {"payload_ts_packets": cc_pkts, "bad_sync": bad_sync, "cc_errors_total": sum(cc_err.values()),
                 "cc_errors_by_pid": {hex(k): v for k, v in cc_err.most_common(8)}}
    # PCR-Jitter
    if pcr_pid_count:
        pid = pcr_pid_count.most_common(1)[0][0]
        s = pcr_samples[pid]
        t0, p0 = s[0]
        offs = [(t - t0) - (p - p0) / 27e6 for t, p in s]  # Sekunden; waechst mit Verzoegerung
        # Drift per linearer Regression (Zeit vs Offset)
        xs = [t - t0 for t, _ in s]
        mx, my = statistics.mean(xs), statistics.mean(offs)
        slope = sum((x - mx) * (y - my) for x, y in zip(xs, offs)) / max(1e-12, sum((x - mx) ** 2 for x in xs))
        detr = sorted((y - slope * x) for x, y in zip(xs, offs))
        base = detr[0]
        jit = sorted((v - base) * 1000 for v in detr)
        res["pcr"] = {"pid": hex(pid), "samples": len(s), "drift_ppm": round(slope * 1e6, 2),
                      "jitter_ms": {"p50": round(pct(jit, 50), 2), "p95": round(pct(jit, 95), 2),
                                    "p99": round(pct(jit, 99), 2), "p999": round(pct(jit, 99.9), 2),
                                    "max": round(jit[-1], 2)}}
    return res


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    url, secs = sys.argv[1], float(sys.argv[2])
    out = None
    rcv = 4 * 1024 * 1024
    if "--json" in sys.argv:
        out = sys.argv[sys.argv.index("--json") + 1]
    if "--rcvbuf" in sys.argv:
        rcv = int(sys.argv[sys.argv.index("--rcvbuf") + 1])
    dump_path = sys.argv[sys.argv.index("--dump") + 1] if "--dump" in sys.argv else None
    res = analyze(url, secs, rcv, dump_path)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    if out:
        with open(out, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=2, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
