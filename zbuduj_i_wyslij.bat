@echo off
rem Kompilacja APK + wyslanie na telefon przez ADB (do folderu Download, instalacja przez KingInstaller).
rem Pelny log kompilacji: build\ostatnia_kompilacja.log
setlocal
cd /d "%~dp0"
set "ADB=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist build mkdir build
echo [1/3] Kompilacja APK (moze potrwac 1-3 min)...
call build_apk.bat > build\ostatnia_kompilacja.log 2>&1
if errorlevel 1 (
    echo BLAD KOMPILACJI - szczegoly w build\ostatnia_kompilacja.log
    echo KOMPILACJA_BLAD>> build\ostatnia_kompilacja.log
    pause
    exit /b 1
)
echo KOMPILACJA_OK>> build\ostatnia_kompilacja.log
echo [2/3] Podlacz telefon kablem USB (debugowanie USB wlaczone)...
"%ADB%" wait-for-device
echo [3/3] Wysylanie DaciaHybridCluster.apk do folderu Download w telefonie...
"%ADB%" push DaciaHybridCluster.apk /sdcard/Download/DaciaHybridCluster.apk >> build\ostatnia_kompilacja.log 2>&1
if errorlevel 1 (
    echo BLAD ADB PUSH>> build\ostatnia_kompilacja.log
    echo Blad wysylania przez ADB.
    pause
    exit /b 1
)
echo ADB_PUSH_OK>> build\ostatnia_kompilacja.log
echo Gotowe! Na telefonie: KingInstaller -^> Download\DaciaHybridCluster.apk
pause
