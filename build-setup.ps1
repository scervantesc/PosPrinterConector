param([string]$Compiler = '', [string]$AppSource = '', [string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
if (!$Compiler) { $Compiler = Join-Path $PSScriptRoot '.verification\inno\compiler\ISCC.exe' }
if (!(Test-Path -LiteralPath $Compiler)) { throw 'Indica -Compiler con la ruta de ISCC.exe (Inno Setup).' }
if (!$AppSource) {
    $release = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'portable') -Directory -Filter 'release-*' | Sort-Object Name -Descending | Select-Object -First 1
    if (!$release) { throw 'Genera primero la aplicacion portatil con package-portable.ps1.' }
    $AppSource = Join-Path $release.FullName 'TicketPrinterServerUI'
}
if (!(Test-Path -LiteralPath (Join-Path $AppSource 'TicketPrinterServerUI.exe'))) { throw 'Falta el ejecutable portatil.' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot ('portable\setup-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
& $Compiler ('/DAppSource=' + $AppSource) ('/DSetupOutput=' + $OutputDirectory) (Join-Path $PSScriptRoot 'packaging\PrinterPOS.iss')
if ($LASTEXITCODE -ne 0) { throw 'Error generando el instalador.' }
Write-Host ('Instalador generado: ' + (Join-Path $OutputDirectory 'PrinterPOS-Setup.exe'))
