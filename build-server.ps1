$ErrorActionPreference = "Stop"

$jdkBin = "C:\Program Files\Java\jdk-26\bin"
$javac = Join-Path $jdkBin "javac.exe"
$jar = Join-Path $jdkBin "jar.exe"
$escposJar = Join-Path (Get-Location) "lib\escpos-coffee-4.1.0.jar"

if (!(Test-Path $escposJar)) {
    throw "Falta dependencia: $escposJar"
}

New-Item -ItemType Directory -Force -Path build,dist | Out-Null
Get-ChildItem build -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
$depCopyDir = Join-Path (Get-Location) "build\deps"
New-Item -ItemType Directory -Force -Path $depCopyDir | Out-Null
$depCopyJar = Join-Path $depCopyDir "escpos-coffee.jar"
Copy-Item -Path $escposJar -Destination $depCopyJar -Force

& $javac -cp $depCopyJar -d build Main.java
Push-Location build
& $jar xf ".\deps\escpos-coffee.jar"
Pop-Location
& $jar --create --file dist/TicketPrinterServer.jar --main-class Main -C build .
Write-Host "OK -> dist/TicketPrinterServer.jar"
