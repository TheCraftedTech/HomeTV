# Erzeugt alle App-Icons/Banner aus dem Home-TV-Branding (Navy #0E1320, Blau #2E7BFF, Schriftzug "Home TV").
# Nur ffmpeg (bereits vorhanden). Aufruf: powershell -File tools\make_icons.ps1
# Vorgaben: developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines
#   Banner 16:9 in mipmap-*: mdpi 160x90 .. xxxhdpi 640x360, Text im Bild, kein Rahmen, Logo in der Safe-Zone
#   Launcher 1:1 in mipmap-*: mdpi 80 .. xxxhdpi 320; Adaptive Icon (API 26+): Vordergrund/Hintergrund 108dp, Logo in 66dp-Safe-Zone
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$res  = Join-Path $root 'app\src\main\res'
$font = 'C\:/Windows/Fonts/segoeuib.ttf'
$navy = '0x0E1320'; $blue = '0x2E7BFF'

function Run($args_) { & ffmpeg -v error -y @args_; if ($LASTEXITCODE -ne 0) { throw "ffmpeg fehlgeschlagen" } }

# ---------- Banner (Basis 1280x720, danach skaliert) ----------
$tmp = Join-Path $env:TEMP 'hometv-icons'; New-Item -ItemType Directory -Force $tmp | Out-Null
$bannerBase = Join-Path $tmp 'banner-1280x720.png'
$vf = "drawtext=fontfile='$font':text='Home TV':fontcolor=white:fontsize=250:x=(w-text_w)/2:y=190," +
      "drawbox=x=(iw-480)/2:y=470:w=480:h=26:color=${blue}@1:t=fill"
Run @('-f','lavfi','-i',"color=c=${navy}:s=1280x720",'-vf',$vf,'-frames:v','1',$bannerBase)

$banner = @{ 'mdpi'=@(160,90); 'hdpi'=@(240,135); 'xhdpi'=@(320,180); 'xxhdpi'=@(480,270); 'xxxhdpi'=@(640,360) }
foreach ($d in $banner.Keys) {
    $dir = Join-Path $res "mipmap-$d"; New-Item -ItemType Directory -Force $dir | Out-Null
    Run @('-i',$bannerBase,'-vf',"scale=$($banner[$d][0]):$($banner[$d][1]):flags=lanczos",'-frames:v','1',(Join-Path $dir 'hometv_banner.png'))
}

# ---------- Legacy-Launcher-Icon 1:1 (Basis 640x640) ----------
$iconBase = Join-Path $tmp 'icon-640.png'
$vf = "drawtext=fontfile='$font':text='TV':fontcolor=white:fontsize=330:x=(w-text_w)/2:y=125," +
      "drawbox=x=(iw-300)/2:y=475:w=300:h=34:color=${blue}@1:t=fill"
Run @('-f','lavfi','-i',"color=c=${navy}:s=640x640",'-vf',$vf,'-frames:v','1',$iconBase)
$icon = @{ 'mdpi'=80; 'hdpi'=120; 'xhdpi'=160; 'xxhdpi'=240; 'xxxhdpi'=320 }
foreach ($d in $icon.Keys) {
    $dir = Join-Path $res "mipmap-$d"
    Run @('-i',$iconBase,'-vf',"scale=$($icon[$d]):$($icon[$d]):flags=lanczos",'-frames:v','1',(Join-Path $dir 'ic_hometv.png'))
}

# ---------- Adaptive Icon: Vordergrund (transparent), Logo innerhalb der 66dp-Safe-Zone von 108dp ----------
$fgBase = Join-Path $tmp 'fg-864.png'   # 108dp * 8
$fc = "[0:v]format=rgba,drawtext=fontfile='$font':text='TV':fontcolor=white:fontsize=300:x=(w-text_w)/2:y=253[t];" +
      "[1:v]format=rgba[bar];[t][bar]overlay=(W-w)/2:583:format=auto"
Run @('-f','lavfi','-i',"color=c=black@0.0:s=864x864,format=rgba",'-f','lavfi','-i',"color=c=${blue}:s=300x34",'-filter_complex',$fc,'-frames:v','1',$fgBase)
$fg = @{ 'mdpi'=108; 'hdpi'=162; 'xhdpi'=216; 'xxhdpi'=324; 'xxxhdpi'=432 }
foreach ($d in $fg.Keys) {
    $dir = Join-Path $res "drawable-$d"; New-Item -ItemType Directory -Force $dir | Out-Null
    Run @('-i',$fgBase,'-vf',"scale=$($fg[$d]):$($fg[$d]):flags=lanczos,format=rgba",'-frames:v','1',(Join-Path $dir 'ic_hometv_foreground.png'))
}

# ---------- Amazon-Appstore-Grafik (nur fuer eine spaetere Store-Einreichung): 1280x720 PNG ohne Transparenz ----------
$brand = Join-Path $root 'docs\branding'; New-Item -ItemType Directory -Force $brand | Out-Null
Copy-Item $bannerBase (Join-Path $brand 'appstore-fire-tv-1280x720.png') -Force
Copy-Item $iconBase   (Join-Path $brand 'icon-640.png') -Force
Write-Host 'Icons erzeugt.'


