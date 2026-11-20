"""MPEG-TS-Zeitstempel-Analyse (Audio/Video-Synchronisation der Quelle). Nur Standardbibliothek.

Aufruf:  python -I tools/ts_sync_check.py <roh.ts>        (Mitschnitt z. B. mit tools/udp_analyze.py --dump)

Fuer jede PES-Spur (Video, AC-3, ...) wird die Vorausschau  ahead = PTS - PCR  zum Zeitpunkt der Ankunft bestimmt.
  * ahead > 0: das Paket kommt vor seinem Praesentationszeitpunkt an (so viel Zeit hat der Player).
  * Ein Player-Puffer (pts-delay) kleiner als  -min(ahead)  fuehrt zu "zu spaeten" Frames/Audio.
  * Der Unterschied der Mediane (Video - Audio) zeigt, um wie viel das Video im Mux frueher gesendet wird
    als das Audio; ein Puffer, der kleiner als dieser Wert ist, kann Audio/Video nicht sauber ausrichten.
Zusaetzlich: PTS-Schrittweiten (Spruenge/Luecken) und Continuity-Counter-Fehler je PID.
"""
import statistics
import sys
from collections import Counter, defaultdict

PTS_MOD = 1 << 33


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))] if v else float("nan")


def read_pts(p, off):
    return (((p[off] >> 1) & 7) << 30) | (p[off + 1] << 22) | ((p[off + 2] >> 1) << 15) | (p[off + 3] << 7) | (p[off + 4] >> 1)


def main(path):
    data = open(path, "rb").read()
    n = len(data) // 188
    if n == 0 or data[0] != 0x47:
        print("keine TS-Daten / kein Sync-Byte am Anfang")
        return 2
    pmt_pids, streams, pcr_pid = set(), {}, None
    cc_last, cc_err = {}, Counter()
    pcr_now = None
    ahead = defaultdict(list)
    pts_list = defaultdict(list)
    pcr_first = pcr_last = None
    sync_bad = 0
    # 1. Durchlauf: PAT/PMT
    for i in range(n):
        p = data[i * 188:(i + 1) * 188]
        if p[0] != 0x47:
            sync_bad += 1
            continue
        pid = ((p[1] & 0x1F) << 8) | p[2]
        pusi = p[1] & 0x40
        afc = (p[3] >> 4) & 3
        if not (afc & 1):
            continue
        off = 4 + (1 + p[4] if afc & 2 else 0)
        # PAT des ganzen Mux (>90 Programme) passt nicht in ein TS-Paket -> PMT direkt an der Table-ID erkennen
        if pusi and pid > 0x14 and p[off:off + 3] != b"\x00\x00\x01" and not streams:
            ptr = p[off]
            s = p[off + 1 + ptr:]
            tid = s[0]
            slen = ((s[1] & 0x0F) << 8) | s[2]
            if tid == 2 and 3 + slen <= len(s):  # PMT (passt vollstaendig in dieses Paket)
                pcr_pid = ((s[8] & 0x1F) << 8) | s[9]
                pil = ((s[10] & 0x0F) << 8) | s[11]
                j = 12 + pil
                while j + 5 <= 3 + slen - 4:
                    st = s[j]
                    epid = ((s[j + 1] & 0x1F) << 8) | s[j + 2]
                    il = ((s[j + 3] & 0x0F) << 8) | s[j + 4]
                    desc = s[j + 5:j + 5 + il]
                    kind = {0x1B: "Video H.264", 0x24: "Video H.265", 0x02: "Video MPEG-2", 0x03: "Audio MPEG", 0x04: "Audio MPEG",
                            0x0F: "Audio AAC", 0x11: "Audio AAC-LATM", 0x81: "Audio AC-3", 0x87: "Audio E-AC-3"}.get(st)
                    if kind is None and st == 0x06:
                        tags = set()
                        k = 0
                        while k + 2 <= len(desc):
                            tags.add(desc[k]); k += 2 + desc[k + 1]
                        kind = "Audio AC-3" if 0x6A in tags else "Audio E-AC-3" if 0x7A in tags else "Teletext" if 0x56 in tags else "Subtitle" if 0x59 in tags else "privat"
                    streams[epid] = kind or f"type 0x{st:02x}"
                    j += 5 + il
    print(f"{n} TS-Pakete, {len(data) / 1e6:.2f} MB, Sync-Fehler: {sync_bad}")
    print(f"PCR-PID: {pcr_pid}  Spuren: " + ", ".join(f"{pid}={k}" for pid, k in sorted(streams.items())))
    es = {pid for pid, k in streams.items() if k.startswith(("Video", "Audio"))}

    # 2. Durchlauf: PCR + PTS + CC
    for i in range(n):
        p = data[i * 188:(i + 1) * 188]
        pid = ((p[1] & 0x1F) << 8) | p[2]
        if pid == 0x1FFF:
            continue
        afc = (p[3] >> 4) & 3
        cc = p[3] & 0x0F
        if afc & 1:
            if pid in cc_last:
                exp = (cc_last[pid] + 1) & 0x0F
                if cc != exp and cc != cc_last[pid]:
                    cc_err[pid] += 1
            cc_last[pid] = cc
        if pid == pcr_pid and afc & 2 and p[4] >= 7 and p[5] & 0x10:
            base = (p[6] << 25) | (p[7] << 17) | (p[8] << 9) | (p[9] << 1) | (p[10] >> 7)
            ext = ((p[10] & 1) << 8) | p[11]
            pcr_now = base * 300 + ext
            if pcr_first is None:
                pcr_first = pcr_now
            pcr_last = pcr_now
        if pid in es and (p[1] & 0x40) and afc & 1 and pcr_now is not None:
            off = 4 + (1 + p[4] if afc & 2 else 0)
            if p[off:off + 3] == b"\x00\x00\x01" and (p[off + 7] & 0x80):
                pts = read_pts(p, off + 9)
                d = ((pts * 300 - pcr_now + (PTS_MOD * 300) // 2) % (PTS_MOD * 300)) - (PTS_MOD * 300) // 2
                ahead[pid].append(d / 27e6)
                pts_list[pid].append(pts)

    dur = ((pcr_last - pcr_first) % (PTS_MOD * 300)) / 27e6 if pcr_first is not None else 0
    print(f"Dauer laut PCR: {dur:.1f} s\n")
    print(f"{'PID':>5} {'Spur':<14} {'PES':>6} | {'ahead min':>9} {'p5':>7} {'Median':>7} {'p95':>7} {'max':>7} (ms) | PTS-Spruenge | CC-Fehler")
    med = {}
    for pid in sorted(ahead):
        a = ahead[pid]
        seq = sorted(set(pts_list[pid])) if streams[pid].startswith("Video") else pts_list[pid]  # Video: B-Frames -> nach Praesentation sortieren
        steps = [((b - x) % PTS_MOD) / 90.0 for x, b in zip(seq, seq[1:])]  # ms
        typ = statistics.median(steps) if steps else 0
        jumps = sum(1 for s in steps if s > 3 * typ or s <= 0) if typ else 0
        med[pid] = statistics.median(a) * 1000
        print(f"{pid:>5} {streams[pid]:<14} {len(a):>6} | {min(a)*1000:9.0f} {pct(a,5)*1000:7.0f} {med[pid]:7.0f} {pct(a,95)*1000:7.0f} {max(a)*1000:7.0f} |"
              f" {jumps:>4} (typ. Schritt {typ:.1f} ms) | {cc_err.get(pid, 0)}")
    vids = [pid for pid in med if streams[pid].startswith("Video")]
    auds = [pid for pid in med if streams[pid].startswith("Audio")]
    if vids and auds:
        for a in auds:
            print(f"\nVideo-Median minus Audio-Median (PID {a}): {med[vids[0]] - med[a]:+.0f} ms "
                  f"(Video wird so viel frueher gesendet als das Audio seiner Praesentation entspricht)")
        worst = min(min(ahead[p]) for p in ahead) * 1000
        print(f"Kleinste Vorausschau ueber alle Spuren: {worst:.0f} ms -> Puffer sollte mindestens {max(0, -worst):.0f} ms + Netzjitter betragen")
    print(f"CC-Fehler gesamt (alle PIDs): {sum(cc_err.values())}   nach PID: " + ", ".join(f"{k}:{v}" for k, v in cc_err.most_common(6)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]) if len(sys.argv) > 1 else print(__doc__) or 2)
