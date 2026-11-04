# Uruchamia emulator Android Auto (DHU) z pelnym, graficznym ekranem zegarow (Canvas Surface)
$sdk = "%USERPROFILE%\AppData\Local\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$dhu = "$sdk\extras\google\auto\desktop-head-unit.exe"
$dhuDir = Split-Path $dhu

Write-Host "1/4 Zatrzymywanie ewentualnych starych instancji DHU..." -ForegroundColor Cyan
Get-Process desktop-head-unit -ErrorAction SilentlyContinue | Stop-Process -Force

Write-Host "2/4 Sprawdzanie polaczenia USB z telefonem..." -ForegroundColor Cyan
& $adb wait-for-device

Write-Host "3/4 Konfiguracja portu 5277 (ADB forward)..." -ForegroundColor Cyan
& $adb forward tcp:5277 tcp:5277

# Sprawdzenie czy serwer AA na telefonie nasluchuje (port 5277 = hex 149D)
$isListening = & $adb shell "cat /proc/net/tcp6 | grep 149D"
if (-not $isListening) {
    Write-Host "Otwieram ustawienia Android Auto na telefonie..." -ForegroundColor Yellow
    & $adb shell input keyevent 82 2>$null
    & $adb shell am start -a android.intent.action.APPLICATION_PREFERENCES -p com.google.android.projection.gearhead 2>$null | Out-Null
    Write-Host "Na telefonie: wcisnij menu 3 kropki (prawy gorny rog) -> 'Uruchom serwer jednostki glownej'..." -ForegroundColor Yellow
    
    while (-not (& $adb shell "cat /proc/net/tcp6 | grep 149D")) {
        Start-Sleep -Milliseconds 800
    }
}

Write-Host "Serwer na telefonie jest aktywny!" -ForegroundColor Green

Write-Host "4/4 Uruchamianie emulatora DHU w trybie pelnego klastra graficznego..." -ForegroundColor Cyan
Set-Location $dhuDir
# default_720p.ini (ekran glowny 1280x720) + cluster_wide.ini (graficzny ekran zegarow 800x480)
& $dhu -c "$dhuDir\config\default_720p.ini" -c "$dhuDir\config\cluster_wide.ini"
