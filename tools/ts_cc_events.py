"""Zeigt, WANN im Roh-TS-Mitschnitt Continuity-Counter-Fehler (= fehlende TS-Pakete) auftreten.

Aufruf:  python -I tools/ts_cc_events.py <roh.ts> [<video-pid>]

Die Zeit stammt aus den PCR der PCR-PID (Standard: erste PID mit PCR). Ausgabe: Ereignisse je Sekundenfenster
und Zusammenfassung "nur beim Start" (< 3 s) gegenueber "waehrend der Wiedergabe".
"""
import collections
import sys


def main(path, pcr_pid=None):
    d = open(path, "rb").read()
    n = len(d) // 188
    cc, pcr, pcr0, ev = {}, None, None, []
    for i in range(n):
        p = d[i * 188:(i + 1) * 188]
        pid = ((p[1] & 0x1F) << 8) | p[2]
        afc = (p[3] >> 4) & 3
        c = p[3] & 15
        if afc & 2 and p[4] >= 7 and p[5] & 0x10 and (pcr_pid is None or pid == pcr_pid):
            pcr_pid = pid
            b = (p[6] << 25) | (p[7] << 17) | (p[8] << 9) | (p[9] << 1) | (p[10] >> 7)
            pcr = b / 90000.0
            if pcr0 is None:
                pcr0 = pcr
        if pid == 0x1FFF or not afc & 1:
            continue
        if pid in cc:
            e = (cc[pid] + 1) & 15
            if c != e and c != cc[pid]:
                ev.append((round(pcr - pcr0, 2) if pcr is not None else -1.0, pid))
        cc[pid] = c
    dur = (pcr - pcr0) if pcr else 0
    start = [e for e in ev if e[0] < 3.0]
    later = [e for e in ev if e[0] >= 3.0]
    print(f"{path}: {n} TS-Pakete, Dauer {dur:.1f} s, PCR-PID {pcr_pid}")
    print(f"CC-Fehler gesamt: {len(ev)}  davon in den ersten 3 s: {len(start)}  danach: {len(later)}")
    by = collections.Counter(round(t) for t, _ in later)
    if later:
        print("Fehler nach dem Start (Sekunde: Anzahl):", dict(sorted(by.items())))
        print("Beispiele (t, PID):", later[:12])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else None) if len(sys.argv) > 1 else print(__doc__) or 2)
