@echo off
rem Wydanie wersji na GitHubie: kompilacja APK + commit + push + GitHub Release z plikiem APK.
rem Mozna uruchamiac wielokrotnie - jesli wydanie juz istnieje, zostanie zaktualizowane (opis + APK).
rem Przy kolejnym wydaniu zmien TAG ponizej oraz versionCode/versionName w app\build.gradle.kts i RELEASE_NOTES.md.
setlocal
cd /d "%~dp0"
set "TAG=v1.1.0"
set "APK=DaciaHybridCluster-%TAG%.apk"

where gh >nul 2>&1 || (
    echo Brak GitHub CLI. Zainstaluj: winget install --id GitHub.cli -e --source winget
    echo a potem otworz NOWE okno i uruchom ten plik ponownie.
    pause
    exit /b 1
)
gh auth status >nul 2>&1 || gh auth login --hostname github.com --web --git-protocol https || (pause & exit /b 1)

echo [1/5] Kompilacja APK...
call build_apk.bat || (echo BLAD KOMPILACJI & pause & exit /b 1)
copy /Y "DaciaHybridCluster.apk" "%APK%" >nul

echo [2/5] Commit zmian...
git add -A
git diff --cached --quiet || git commit -m "Wydanie %TAG%" || (echo BLAD git commit & pause & exit /b 1)

echo [3/5] Wysylanie na GitHub...
git push origin main || (echo BLAD git push & pause & exit /b 1)

echo [4/5] Tag %TAG% na ostatni commit...
git tag -f %TAG% >nul
git push -f origin %TAG% || (echo BLAD wysylania tagu & pause & exit /b 1)

echo [5/5] Wydanie %TAG% na GitHubie...
gh release view %TAG% >nul 2>&1
if errorlevel 1 (
    gh release create %TAG% "%APK%" --verify-tag --title "Dacia Telemetry %TAG%" --notes-file RELEASE_NOTES.md || (echo BLAD gh release create & pause & exit /b 1)
) else (
    echo Wydanie juz istnieje - aktualizuje opis i plik APK...
    gh release edit %TAG% --title "Dacia Telemetry %TAG%" --notes-file RELEASE_NOTES.md || (echo BLAD gh release edit & pause & exit /b 1)
    gh release upload %TAG% "%APK%" --clobber || (echo BLAD wysylania APK & pause & exit /b 1)
)
del "%APK%"
echo Gotowe! Wydanie: https://github.com/N3j10/DaciaHybridCluster/releases/tag/%TAG%
pause
