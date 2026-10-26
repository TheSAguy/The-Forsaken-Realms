@echo off
rem Refresh the agent game folder from the live folder after a package (robocopy copies only what changed).
rem Refuses while the agent game runs (it holds its jar) - stop it first (the tfr-play skill's stop line).
rem Mirrors ONLY the game folder; the profile next to it (saves, prefs, log) is never touched.
rem Round 739: the guard matched '*TFR-Agent*' (the F:\FORGE folder before round 267), so it never fired. It now lists
rem the agent folder under both names (C:\TFR is a junction to C:\CLAUDE\PROJECTS\TFR1) - the same two as
rem agent_stop.ps1 and the skill's stop line; change all three together. -like is case-insensitive; '/' folds to '\'.
rem Exit 3 = an agent game runs; any other non-zero = the check itself failed. Both refuse.
powershell -NoProfile -Command "$d = 'C:\TFR\agent\The Forsaken Realms\', 'C:\CLAUDE\PROJECTS\TFR1\agent\The Forsaken Realms\'; $p = @(Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" -ErrorAction Stop | Where-Object { $cl = \"$($_.CommandLine)\" -replace '/', '\'; @($d | Where-Object { $cl -like \"*$_*\" }).Count -gt 0 }); if ($p.Count -gt 0) { Write-Output ('Agent game PID(s): ' + ($p.ProcessId -join ', ')); exit 3 }; exit 0"
if errorlevel 4 (echo Could not check for a running agent game - nothing copied. & exit /b 1)
if errorlevel 3 (echo The agent game is running - stop it first ^(the tfr-play skill's stop line^). Nothing copied. & exit /b 1)
if errorlevel 1 (echo Could not check for a running agent game - nothing copied. & exit /b 1)
robocopy "C:\TFR\live\The Forsaken Realms" "C:\TFR\agent\The Forsaken Realms" /MIR /R:2 /W:2 /NFL /NDL /NP
if %ERRORLEVEL% GEQ 8 (echo robocopy failed with %ERRORLEVEL% & exit /b 1)
echo Agent game folder is in sync with the live folder.
exit /b 0
