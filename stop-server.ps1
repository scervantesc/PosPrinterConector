$ErrorActionPreference = "Stop"

$pidFile = ".ticket-printer.pid"
if (!(Test-Path $pidFile)) {
    Write-Host "No existe archivo PID. Nada que detener."
    exit 0
}

$targetPid = Get-Content $pidFile | Select-Object -First 1
if ($targetPid -and (Get-Process -Id $targetPid -ErrorAction SilentlyContinue)) {
    Stop-Process -Id $targetPid -Force
    Write-Host ("Servicio detenido. PID: {0}" -f $targetPid)
} else {
    Write-Host "El proceso ya no estaba activo."
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
