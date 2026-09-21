# Stops the AGENT game only: the javaw.exe whose command line names the agent's own folder (agent_launch.cmd
# passes the jar - and, in the dev loop, the classes dir - by absolute path for exactly this reason). The
# player's own game runs from C:\TFR\live and is never matched; the second clause says so explicitly rather
# than relying on the first, because this script existing and WORKING is what stands between a stuck agent and
# someone reaching for a blanket "stop every javaw", which once ended the player's session mid-duel.
#
# Round 267: the pattern was '*TFR-Agent*', from when the agent lived in F:\FORGE\TFR-Agent. The move to
# C:\TFR\agent left nothing matching it, so this script reported "No agent game running." while the agent was
# running - silently useless, in the one place where being useless is dangerous.
$procs = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object {
    $_.CommandLine -like '*\TFR\agent\*' -and $_.CommandLine -notlike '*\TFR\live\*'
})
if ($procs.Count -eq 0) { Write-Output "No agent game running."; exit 0 }
foreach ($p in $procs) {
    Write-Output ("Stopping agent game PID {0}" -f $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force
}
