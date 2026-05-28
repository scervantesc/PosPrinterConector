$ErrorActionPreference = "Stop"

Write-Host "== Verificacion de entorno Java para instalador Windows =="
$tools = @("java", "javac", "jar", "jpackage", "jlink")
foreach ($t in $tools) {
    $cmd = Get-Command $t -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Host ("[OK] {0} -> {1}" -f $t, $cmd.Source)
    } else {
        Write-Host ("[FALTA] {0}" -f $t)
    }
}

Write-Host ""
Write-Host "Prueba de compilacion y ejecucion:"
javac Main.java
if ($LASTEXITCODE -ne 0) { throw "No compilo Main.java" }
java Main
