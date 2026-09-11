# Stops the AGENT game only: the javaw.exe whose command line names the TFR-Agent jar (agent_launch.cmd passes it
# by absolute path for exactly this reason). The player's own game runs from F:\FORGE\TFR-Standalone and is never
# matched.
$procs = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*TFR-Agent*' })
if ($procs.Count -eq 0) { Write-Output "No agent game running."; exit 0 }
foreach ($p in $procs) {
    Write-Output ("Stopping agent game PID {0}" -f $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force
}
