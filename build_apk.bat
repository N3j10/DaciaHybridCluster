@echo off
rem Buduje APK debug sprawdzona lokalna wersja Gradle 8.10.2 (zgodna z AGP 8.7.2)
rem i kopiuje wynik do DaciaHybridCluster.apk (uzywa go install_phone.bat).
rem Uzycie: build_apk.bat          - sam APK
rem         build_apk.bat test     - najpierw testy JVM (symulacja na logach), potem APK
setlocal
set "JAVA_HOME=%USERPROFILE%\.gemini\antigravity\scratch\jdk-17"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "GRADLE=%USERPROFILE%\.gemini\antigravity\scratch\gradle\gradle-8.10.2\bin\gradle.bat"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"

if /I "%1"=="test" (
    call "%GRADLE%" testDebugUnitTest || exit /b 1
)

call "%GRADLE%" assembleDebug || exit /b 1
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "DaciaHybridCluster.apk" >nul
echo Gotowe: DaciaHybridCluster.apk
