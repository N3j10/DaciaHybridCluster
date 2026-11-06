@echo off
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "APK=%~dp0DaciaHybridCluster.apk"

echo Czekam na podlaczenie telefonu USB...
%ADB% wait-for-device
echo Instalowanie aplikacji ze statusem Google Play...
%ADB% install -r -i com.android.vending "%APK%"
echo Gotowe!
pause
