param([string]$JdkBin = "C:\Program Files\Java\jdk-26\bin")
$ErrorActionPreference = "Stop"

if (!(Test-Path (Join-Path $jdkBin "javac.exe"))) {
    throw "No se encontro javac.exe en $jdkBin"
}

$javac = Join-Path $jdkBin "javac.exe"
$jar = Join-Path $jdkBin "jar.exe"
$jpackage = Join-Path $jdkBin "jpackage.exe"

New-Item -ItemType Directory -Force -Path build,dist,installer | Out-Null

& (Join-Path $PSScriptRoot 'build-server.ps1') -JdkBin $JdkBin
Copy-Item -LiteralPath dist/TicketPrinterServer.jar -Destination dist/PluginApp.jar -Force

Write-Host "JAR generado: dist/PluginApp.jar"

& $jpackage --type msi `
    --name PluginApp `
    --input dist `
    --main-jar PluginApp.jar `
    --main-class Main `
    --dest installer `
    --win-shortcut `
    --win-menu `
    --app-version 1.0.0

$msi = Get-ChildItem installer -Filter *.msi -ErrorAction SilentlyContinue
if (!$msi) {
    throw "No se genero el .msi. Falta WiX Toolset en PATH."
}

Write-Host "Instalador generado: $($msi.FullName)"
