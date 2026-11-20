# Home TV - UDP-Verluste des Fire TV live beobachten (rein lesend, Abbruch mit Strg+C)
#
# Liest alle 5 s /proc/net/snmp ("Udp:"-Zeile) per ADB und zeigt die Aenderung:
#   InDatagrams   empfangene UDP-Datagramme (Summe aller Apps; im UDP-Loopback-Modus zaehlt jedes Paket 2x)
#   RcvbufErrors  Datagramme, die verworfen wurden, weil ein Socket-Puffer voll war  (= Bildfehler-Kandidat)
# Starte das Skript, dann auf dem Fire TV Home TV benutzen (Senderwechsel, 5 Minuten schauen).
# Wenn RcvbufErrors waehrend der Wiedergabe steigt, gehen Pakete lokal auf dem Geraet verloren.
param([string]$Ip = "")
$root = Split-Path -Parent $PSScriptRoot
$env:ANDROID_USER_HOME = Join-Path $root ".android-user"
$adb = Join-Path $root "tools\platform-tools\adb.exe"
if (-not $Ip) { $Ip = (Get-Content (Join-Path $root "firetv-ip.txt") -ErrorAction Stop | Select-Object -First 1).Trim() }
$dev = "${Ip}:5555"
& $adb connect $dev | Out-Null

function Get-Udp {
    $raw = & $adb -s $dev shell "grep '^Udp:' /proc/net/snmp"
    $h = ($raw[0] -split '\s+')[1..8]; $v = ($raw[1] -split '\s+')[1..8]
    $o = @{}; for ($i = 0; $i -lt $h.Count; $i++) { $o[$h[$i]] = [int64]$v[$i] }
    return $o
}

Write-Host "UDP-Monitor fuer $dev (Strg+C beendet)"
$prev = Get-Udp
Write-Host ("Start: InDatagrams={0}  RcvbufErrors={1}" -f $prev.InDatagrams, $prev.RcvbufErrors)
"{0,-9} {1,12} {2,14} {3,16}" -f "Zeit", "neue Pakete", "neue Verluste", "Verluste gesamt"
while ($true) {
    Start-Sleep -Seconds 5
    $cur = Get-Udp
    $dIn = $cur.InDatagrams - $prev.InDatagrams
    $dErr = $cur.RcvbufErrors - $prev.RcvbufErrors
    $line = "{0,-9} {1,12} {2,14} {3,16}" -f (Get-Date -Format HH:mm:ss), $dIn, $dErr, $cur.RcvbufErrors
    if ($dErr -gt 0) { Write-Host $line -ForegroundColor Red } else { Write-Host $line }
    $prev = $cur
}
