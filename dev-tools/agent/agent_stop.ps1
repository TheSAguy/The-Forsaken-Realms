# Stops the AGENT game only: the javaw.exe whose command line names the agent's own folder (agent_launch.cmd
# passes the jar - and, in the dev loop, the classes dir - by absolute path for exactly this reason). The
# player's own game runs from ...\live\The Forsaken Realms and is never matched; the second clause says so explicitly
# rather than relying on the first, because this script existing and WORKING is what stands between a stuck agent
# and someone reaching for a blanket "stop every javaw", which once ended the player's session mid-duel.
#
# Round 267: the pattern was '*TFR-Agent*', from when the agent lived in F:\FORGE\TFR-Agent. The move to
# C:\TFR\agent left nothing matching it, so this script reported "No agent game running." while the agent was
# running - silently useless, in the one place where being useless is dangerous.
#
# Round 739: the project moved to C:\CLAUDE\PROJECTS\TFR1 and C:\TFR is a junction to it, so the agent folder has two
# names. agent_launch.cmd uses C:\TFR today; a launch through the real path must match too. The two folders are
# listed in full (never a bare '*\agent\The Forsaken Realms\*'), so TFR 2's games and the player's game stay out.
# -like is case-insensitive, and '/' is folded to '\' first, so "c:/tfr/agent/..." matches as well. The same two
# folders are in agent_sync.cmd and the tfr-play skill's stop line - change all three together.
$AgentGameDirs = @(
    'C:\TFR\agent\The Forsaken Realms\',
    'C:\CLAUDE\PROJECTS\TFR1\agent\The Forsaken Realms\'
)
$procs = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object {
    $cl = "$($_.CommandLine)" -replace '/', '\'
    @($AgentGameDirs | Where-Object { $cl -like "*$_*" }).Count -gt 0 -and $cl -notlike '*\live\The Forsaken Realms\*'
})
if ($procs.Count -eq 0) { Write-Output "No agent game running."; exit 0 }
foreach ($p in $procs) {
    Write-Output ("Stopping agent game PID {0}" -f $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force
}
