param([switch]$OpenBrowser)
$ErrorActionPreference = "Stop"

$jarPath = Join-Path $PSScriptRoot "dist\TicketPrinterServer.jar"
if (!(Test-Path $jarPath)) {
    throw "No existe $jarPath. Ejecuta primero build-server.ps1"
}

function Get-PrinterHealth {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:5001/health' -TimeoutSec 2
        return ($health.ok -and $health.service -eq 'ticket-printer')
    } catch { return $false }
}

if (Get-PrinterHealth) {
    Write-Host 'El servicio ya esta activo en http://127.0.0.1:5001/'
    if ($OpenBrowser) { Start-Process 'http://127.0.0.1:5001/' }
    exit 0
}

$candidates = @()
if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) { $candidates += $pathJava.Source }
$candidates += 'C:\Program Files\Java\jdk-26\bin\java.exe'
$candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$java = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$java) { throw 'No se encontro Java. Configura JAVA_HOME con un JDK 21 o superior.' }

$stdoutLog = Join-Path $PSScriptRoot '.ticket-printer.stdout.log'
$stderrLog = Join-Path $PSScriptRoot '.ticket-printer.stderr.log'
$proc = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jarPath + '"'), '--no-ui') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
$ready = $false
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    if (Get-PrinterHealth) { $ready = $true; break }
    $proc.Refresh()
    if ($proc.HasExited) { break }
    Start-Sleep -Milliseconds 300
}
if (!$ready) {
    $details = Get-Content -LiteralPath $stderrLog -Raw -ErrorAction SilentlyContinue
    throw "El servicio no pudo iniciar. Revisa $stderrLog`n$details"
}
Set-Content -LiteralPath (Join-Path $PSScriptRoot '.ticket-printer.pid') -Value $proc.Id -Encoding Ascii
Write-Host ("Servicio iniciado en segundo plano. PID: {0}" -f $proc.Id)
Write-Host "Endpoint: http://127.0.0.1:5001/printer"
if ($OpenBrowser) { Start-Process 'http://127.0.0.1:5001/' }
