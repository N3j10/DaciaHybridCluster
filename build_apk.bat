@echo off
set "JAVA_HOME=%USERPROFILE%\.gemini\antigravity\scratch\jdk-17"
set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d %USERPROFILE%\.gemini\antigravity\scratch\DaciaHybridCluster
call %USERPROFILE%\.gemini\antigravity\scratch\gradle\gradle-8.10.2\bin\gradle.bat assembleDebug
