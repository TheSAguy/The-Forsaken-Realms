@echo off
rem Refresh the agent game folder from the live folder after a package (robocopy copies only what changed).
rem Refuses while the agent game runs (it holds its jar) - run agent_stop.ps1 first.
rem Mirrors ONLY the game folder; the profile next to it (saves, prefs, log) is never touched.
powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" | Where-Object { $_.CommandLine -like '*TFR-Agent*' }) { exit 1 }"
if errorlevel 1 (echo The agent game is running - stop it first with agent_stop.ps1. & exit /b 1)
robocopy "F:\FORGE\TFR-Standalone\The Forsaken Realms" "F:\FORGE\TFR-Agent\The Forsaken Realms" /MIR /R:2 /W:2 /NFL /NDL /NP
if %ERRORLEVEL% GEQ 8 (echo robocopy failed with %ERRORLEVEL% & exit /b 1)
echo Agent game folder is in sync with the live folder.
exit /b 0
