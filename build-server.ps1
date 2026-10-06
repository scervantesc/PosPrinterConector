param([string]$JdkBin = "")
$ErrorActionPreference = "Stop"

if (!$JdkBin) {
    if ($env:JAVA_HOME) { $JdkBin = Join-Path $env:JAVA_HOME "bin" }
    elseif (Get-Command javac.exe -ErrorAction SilentlyContinue) { $JdkBin = Split-Path (Get-Command javac.exe).Source }
    elseif (Test-Path "C:\Program Files\Java\jdk-26\bin\javac.exe") { $JdkBin = "C:\Program Files\Java\jdk-26\bin" }
    else { $JdkBin = "C:\Program Files\Android\Android Studio\jbr\bin" }
}
if (!(Test-Path (Join-Path $JdkBin "javac.exe"))) { throw "Indica -JdkBin con una carpeta de JDK que contenga javac.exe y jar.exe." }
$javac = Join-Path $jdkBin "javac.exe"
$jar = Join-Path $jdkBin "jar.exe"
$escposJar = Join-Path (Get-Location) "lib\escpos-coffee-4.1.0.jar"

if (!(Test-Path $escposJar)) {
    throw "Falta dependencia: $escposJar"
}

New-Item -ItemType Directory -Force -Path build,dist | Out-Null
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) 'build'))
if ($buildRoot -ne [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))) { throw "Ejecuta este script desde la carpeta del proyecto." }
Get-ChildItem -LiteralPath $buildRoot -Force | Remove-Item -Recurse -Force
$depCopyDir = Join-Path (Get-Location) "build\deps"
New-Item -ItemType Directory -Force -Path $depCopyDir | Out-Null
$depCopyJar = Join-Path $depCopyDir "escpos-coffee.jar"
Copy-Item -Path $escposJar -Destination $depCopyJar -Force

& $javac -cp $depCopyJar -d build Main.java
if ($LASTEXITCODE -ne 0) { throw "Error compilando Main.java." }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'web') -Destination $buildRoot -Recurse -Force
Push-Location build
& $jar xf ".\deps\escpos-coffee.jar"
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Error extrayendo dependencia." }
Pop-Location
& $jar --create --file dist/TicketPrinterServer.jar --main-class Main -C build .
if ($LASTEXITCODE -ne 0) { throw "Error generando JAR." }
Write-Host "OK -> dist/TicketPrinterServer.jar"
