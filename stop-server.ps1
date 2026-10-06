$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $PSScriptRoot '.ticket-printer.pid'
$jarPath = Join-Path $PSScriptRoot 'dist\TicketPrinterServer.jar'
$targets = @()
$listeners = @(Get-NetTCPConnection -LocalPort 5001 -State Listen -ErrorAction SilentlyContinue)
$targets += $listeners | Select-Object -ExpandProperty OwningProcess -Unique
if (Test-Path -LiteralPath $pidFile) {
    $savedPid = 0
    $stored = Get-Content -LiteralPath $pidFile | Select-Object -First 1
    if ([int]::TryParse($stored, [ref]$savedPid) -and $savedPid -gt 0) { $targets += $savedPid }
}
foreach ($processId in ($targets | Select-Object -Unique)) {
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (!$process) { continue }
    try {
        $info = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction Stop
        $isProjectJava = $info.Name -in @('java.exe', 'javaw.exe') -and $info.CommandLine -and ($info.CommandLine.IndexOf($jarPath, [StringComparison]::OrdinalIgnoreCase) -ge 0)
        $isPrinterApp = $info.Name -in @('TicketPrinterServer.exe', 'TicketPrinterServerUI.exe')
        if (!$isProjectJava -and !$isPrinterApp) { throw "El PID $processId no corresponde a esta app. No se detuvo." }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        $process.WaitForExit(5000) | Out-Null
        Write-Host "Proceso detenido: $processId"
    } catch {
        throw "No se pudo detener el PID $processId. Si indica acceso denegado, abre PowerShell como administrador. Detalle: $($_.Exception.Message)"
    }
}
$remaining = @(Get-NetTCPConnection -LocalPort 5001 -State Listen -ErrorAction SilentlyContinue)
if ($remaining.Count -gt 0) { throw 'El puerto 5001 sigue ocupado. No se elimino el archivo PID.' }
$responding = $false
try {
    Invoke-WebRequest 'http://127.0.0.1:5001/health' -TimeoutSec 2 -UseBasicParsing | Out-Null
    $responding = $true
} catch {
    if ($_.Exception.Response) { $responding = $true }
}
if ($responding) { throw 'El puerto 5001 sigue respondiendo. No se elimino el archivo PID.' }
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Host 'Confirmado: el servicio del puerto 5001 esta detenido.'
