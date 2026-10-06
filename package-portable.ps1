param([string]$Destination = "", [string]$JdkBin = "")
$ErrorActionPreference = 'Stop'
$jarPath = Join-Path $PSScriptRoot 'dist\TicketPrinterServer.jar'
if (!(Test-Path -LiteralPath $jarPath)) { throw 'Compila primero con build-server.ps1.' }
if (!$Destination) { $Destination = Join-Path $PSScriptRoot 'portable' }
$appDir = Join-Path $Destination 'TicketPrinterServerUI'
if (Test-Path -LiteralPath $appDir) { throw "La carpeta $appDir ya existe. Indica otra -Destination para conservar la copia anterior." }
if (!$JdkBin -and $env:JAVA_HOME) { $JdkBin = Join-Path $env:JAVA_HOME 'bin' }
$jpackage = if ($JdkBin) { Join-Path $JdkBin 'jpackage.exe' } else { '' }
if ($jpackage -and (Test-Path -LiteralPath $jpackage)) {
    $inputDir = Join-Path $Destination 'jar-input'
    New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination $inputDir
    & $jpackage --type app-image --name TicketPrinterServerUI --input $inputDir --main-jar TicketPrinterServer.jar --main-class Main --dest $Destination --app-version 1.2.0
    if ($LASTEXITCODE -ne 0) { throw 'Error generando ejecutable con jpackage.' }
} else {
    # Reuse the existing jpackage launcher and compatible Java 26 runtime.
    $template = Join-Path $PSScriptRoot 'installer\TicketPrinterServerUI'
    $launcher = Join-Path $template 'TicketPrinterServerUI.exe'
    if (!(Test-Path -LiteralPath $launcher) -or !(Test-Path -LiteralPath (Join-Path $template 'runtime\bin\server\jvm.dll'))) {
        throw 'Falta la base portatil. Indica -JdkBin con un JDK que incluya jpackage.exe.'
    }
    New-Item -ItemType Directory -Path (Join-Path $appDir 'app') -Force | Out-Null
    Copy-Item -LiteralPath $launcher -Destination $appDir
    Copy-Item -LiteralPath (Join-Path $template 'runtime') -Destination $appDir -Recurse
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $appDir 'app\TicketPrinterServer.jar')
    $config = '[Application]', 'app.classpath=$APPDIR\TicketPrinterServer.jar', 'app.mainclass=Main', '', '[JavaOptions]', 'java-options=-Djpackage.app-version=1.2.0'
    Set-Content -LiteralPath (Join-Path $appDir 'app\TicketPrinterServerUI.cfg') -Value $config -Encoding ASCII
}
Set-Content -LiteralPath (Join-Path $appDir 'LEEME.txt') -Encoding UTF8 -Value @(
    'Abre TicketPrinterServerUI.exe. La app aparece en la bandeja junto al reloj.',
    'Desde el icono puedes abrir la ventana y la configuracion web: http://127.0.0.1:5001/',
    'Conserva toda esta carpeta; el EXE necesita app y runtime. Java viene incluido.',
    'Configuracion y logotipo: %APPDATA%\TicketPrinter.',
    'Cierra otra instancia del programa antes de abrir esta version.'
)
Write-Host "Ejecutable generado: $appDir\TicketPrinterServerUI.exe"
