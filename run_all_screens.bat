@echo off
rem Capture the store screenshots on the phone, 7" and 10" emulators one after
rem another, then re-render the store art. Boots and shuts down each AVD by
rem itself; start it and walk away. See tools\run_all_screens.ps1.
rem
rem   run_all_screens.bat                        all three slots, all 18 languages
rem   run_all_screens.bat -Upload                ...then push to Play (upload_screens)
rem   run_all_screens.bat -Slots phone -Locales ru-RU,ar

chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run_all_screens.ps1" %*
set RESULT=%ERRORLEVEL%
echo.
if %RESULT%==0 (echo All done.) else (echo Finished with errors, see the log above.)
pause
exit /b %RESULT%
