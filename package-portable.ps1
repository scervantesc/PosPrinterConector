$ErrorActionPreference = "Stop"

$jdkBin = "C:\Program Files\Java\jdk-26\bin"
$jpackage = Join-Path $jdkBin "jpackage.exe"
if (!(Test-Path $jpackage)) {
    throw "No se encontro jpackage en $jdkBin"
}

if (!(Test-Path "dist\TicketPrinterServer.jar")) {
    throw "No existe dist\TicketPrinterServer.jar. Ejecuta build-server.ps1"
}

New-Item -ItemType Directory -Force -Path installer | Out-Null
& $jpackage --type app-image --name TicketPrinterServer --input dist --main-jar TicketPrinterServer.jar --main-class Main --dest installer --app-version 1.0.0
Write-Host "OK -> installer\TicketPrinterServer\TicketPrinterServer.exe"
