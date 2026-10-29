@echo off
rem The Forsaken Realms - the AGENT game (MOD_SCOPE #117). Isolated from the player's own game:
rem   game folder  <root>\The Forsaken Realms   (a mirror of the live folder, refreshed by agent_sync.cmd)
rem   profile      APPDATA=<root>\profile        (saves, preferences, forge.log in profile\ForsakenRealms)
rem   cache        LOCALAPPDATA=<root>\localappdata   (round 860: fonts, card art, skins, db - ForsakenRealms\Cache and
rem                Forge\Cache\pics. Before it the agent shared the player's %LOCALAPPDATA% caches, and the player's
rem                game hung in card loading twice on 2026-10-29 while an agent game ran.)
rem <root> is, in order: %TFR_AGENT_ROOT% if set; this script's own folder when it sits in an agent root (a copy of
rem this file next to a "The Forsaken Realms" folder); else C:\TFR\agent. An agent-NNN copy therefore never points
rem back at the shared agent: set TFR_AGENT_ROOT, or copy this file into the copy's root.
rem The agent bridge listens on 127.0.0.1:8765 - drive it with dev-tools\agent\tfr_agent.py.
rem Usage:  agent_launch.cmd                 fair play (fog of war, no console commands)
rem         agent_launch.cmd cheats          console commands + the fog-free state, for testing
rem         agent_launch.cmd cheats C:\dir   dev loop: classes compiled into C:\dir go AHEAD of the jar (no Maven)
rem         agent_launch.cmd fair C:\dir     the same, without cheats
rem The jar is named by its absolute path so the process is recognizable (agent_stop.ps1 matches the agent folder).
setlocal
set "ROOT=%TFR_AGENT_ROOT%"
if not defined ROOT if exist "%~dp0The Forsaken Realms\PACKAGE_OK.txt" set "ROOT=%~dp0"
if not defined ROOT set "ROOT=C:\TFR\agent"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
if not exist "%ROOT%\The Forsaken Realms\PACKAGE_OK.txt" (echo No agent game folder at %ROOT%\The Forsaken Realms - nothing started. & exit /b 1)
rem Never the player's own game: refuse a root that is (or is inside) the live folder.
set "CHK=%ROOT%\"
if /i not "%CHK:\live\=%"=="%CHK%" (echo %ROOT% is the live game - nothing started. & exit /b 1)
set "APPDATA=%ROOT%\profile"
set "LOCALAPPDATA=%ROOT%\localappdata"
if not exist "%LOCALAPPDATA%" mkdir "%LOCALAPPDATA%"
set "TFR_AGENT_PORT=8765"
set "TFR_AGENT_CHEATS="
if /i "%~1"=="cheats" set "TFR_AGENT_CHEATS=1"
rem Round 385: whichever engine version the folder holds (2.0.16-SNAPSHOT since the 09.30 merge).
for %%J in ("%ROOT%\The Forsaken Realms\forge-gui-mobile-dev-*-jar-with-dependencies.jar") do set "JAR=%%~fJ"
set "OPENS=--add-opens java.desktop/java.beans=ALL-UNNAMED --add-opens java.desktop/javax.swing.border=ALL-UNNAMED --add-opens java.desktop/javax.swing.event=ALL-UNNAMED --add-opens java.desktop/sun.swing=ALL-UNNAMED --add-opens java.desktop/java.awt.image=ALL-UNNAMED --add-opens java.desktop/java.awt.color=ALL-UNNAMED --add-opens java.desktop/sun.awt.image=ALL-UNNAMED --add-opens java.desktop/javax.swing=ALL-UNNAMED --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8"
cd /d "%ROOT%\The Forsaken Realms"
if "%~2"=="" (
  start "" "C:\Program Files\Java\jdk-22\bin\javaw.exe" -Xmx4096m %OPENS% -jar "%JAR%"
) else (
  start "" "C:\Program Files\Java\jdk-22\bin\javaw.exe" -Xmx4096m %OPENS% -cp "%~2;%JAR%" forge.app.Main
)
endlocal
