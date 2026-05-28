$ErrorActionPreference = "Stop"

$jarPath = Join-Path (Get-Location) "dist\TicketPrinterServer.jar"
if (!(Test-Path $jarPath)) {
    throw "No existe $jarPath. Ejecuta primero build-server.ps1"
}

$javaw = "C:\Program Files\Java\jdk-26\bin\javaw.exe"
if (!(Test-Path $javaw)) {
    $javaw = "javaw"
}

$proc = Start-Process -FilePath $javaw -ArgumentList "-jar `"$jarPath`"" -PassThru
Set-Content -Path ".ticket-printer.pid" -Value $proc.Id -Encoding Ascii
Write-Host ("Servicio iniciado en segundo plano. PID: {0}" -f $proc.Id)
Write-Host "Endpoint: http://127.0.0.1:5001/printer"
