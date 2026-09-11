@echo off
rem The Forsaken Realms - the AGENT game (MOD_SCOPE #117). Isolated from the player's own game:
rem   game folder  F:\FORGE\TFR-Agent\The Forsaken Realms   (a mirror of the live folder, refreshed by agent_sync.cmd)
rem   profile      APPDATA=F:\FORGE\TFR-Agent\profile        (saves, preferences, forge.log in profile\ForsakenRealms)
rem The agent bridge listens on 127.0.0.1:8765 - drive it with dev-tools\agent\tfr_agent.py.
rem Usage:  agent_launch.cmd                 fair play (fog of war, no console commands)
rem         agent_launch.cmd cheats          console commands + the fog-free state, for testing
rem         agent_launch.cmd cheats C:\dir   dev loop: classes compiled into C:\dir go AHEAD of the jar (no Maven)
rem         agent_launch.cmd fair C:\dir     the same, without cheats
rem The jar is named by its absolute path so the process is recognizable (agent_stop.ps1 matches "TFR-Agent").
setlocal
set "APPDATA=F:\FORGE\TFR-Agent\profile"
set "TFR_AGENT_PORT=8765"
set "TFR_AGENT_CHEATS="
if /i "%~1"=="cheats" set "TFR_AGENT_CHEATS=1"
set "JAR=F:\FORGE\TFR-Agent\The Forsaken Realms\forge-gui-mobile-dev-2.0.15-SNAPSHOT-jar-with-dependencies.jar"
set "OPENS=--add-opens java.desktop/java.beans=ALL-UNNAMED --add-opens java.desktop/javax.swing.border=ALL-UNNAMED --add-opens java.desktop/javax.swing.event=ALL-UNNAMED --add-opens java.desktop/sun.swing=ALL-UNNAMED --add-opens java.desktop/java.awt.image=ALL-UNNAMED --add-opens java.desktop/java.awt.color=ALL-UNNAMED --add-opens java.desktop/sun.awt.image=ALL-UNNAMED --add-opens java.desktop/javax.swing=ALL-UNNAMED --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8"
cd /d "F:\FORGE\TFR-Agent\The Forsaken Realms"
if "%~2"=="" (
  start "" "C:\Program Files\Java\jdk-22\bin\javaw.exe" -Xmx4096m %OPENS% -jar "%JAR%"
) else (
  start "" "C:\Program Files\Java\jdk-22\bin\javaw.exe" -Xmx4096m %OPENS% -cp "%~2;%JAR%" forge.app.Main
)
endlocal
