# Wrapper para backtest C4/P4 Opening con entry window 9:30-9:36 ET
# Invoca C4P4OpeningBacktestIT directamente (unica forma de override entry window)
# Resultados en analysis/backtest_c4_p4_opening_<FECHA>/
#
# IMPORTANTE: C4P4OpeningBacktestIT escribe sus outputs directamente al directorio
# analysis/backtest_c4_p4_opening_<FECHA>/ - este script NO copia archivos post-run.
#
# Log completo se guarda en tiempo real en logs/backtest_c4_p4_<timestamp>.log
# (sobrevive a Ctrl+C: cada linea se escribe inmediatamente)
#
# Uso: .\scripts\run-backtest-c4-p4.ps1

$ErrorActionPreference = "Stop"

$date = Get-Date -Format "yyyy-MM-dd"
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outputDir = "analysis/backtest_c4_p4_opening_$date"

# Crear carpeta de logs si no existe
$logsDir = "logs"
if (-not (Test-Path $logsDir)) {
    New-Item -ItemType Directory -Path $logsDir | Out-Null
}
$logFile = Join-Path $logsDir "backtest_c4_p4_$timestamp.log"

Write-Host "=========================================="
Write-Host "  Backtest C4/P4 Opening - $date"
Write-Host "  Entry window: 9:30-9:36 AM ET"
Write-Host "  Tickers: 16 (HOT + Tactical)"
Write-Host "  Output dir: $outputDir"
Write-Host "  Log file:   $logFile"
Write-Host "=========================================="
Write-Host ""
Write-Host "Corriendo: ./gradlew slowTest --tests `"*C4P4OpeningBacktestIT*`""
Write-Host "Tip: podes seguir el log en otra terminal con:"
Write-Host "  Get-Content '$logFile' -Tail 50 -Wait"
Write-Host ""

# Ejecutar con redireccion directa a archivo Y consola en tiempo real.
# ForEach-Object con Out-File -Append escribe linea a linea, asi que
# si matas el proceso con Ctrl+C el log parcial queda guardado.
& ./gradlew slowTest --tests "*C4P4OpeningBacktestIT*" 2>&1 | ForEach-Object {
    $_ | Out-File -FilePath $logFile -Append -Encoding utf8
    Write-Host $_
}

$exitCode = $LASTEXITCODE

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Error "Gradle fallo con codigo $exitCode. Revisa el log en: $logFile"
    exit $exitCode
}

Write-Host ""
Write-Host "=========================================="
Write-Host "Resultados en: $outputDir"
Write-Host "  - summary.md"
Write-Host "  - C4_trades.tsv"
Write-Host "  - P4_trades.tsv"
Write-Host "  - C4_by_year.tsv"
Write-Host "  - P4_by_year.tsv"
Write-Host "  - C4_by_ticker.tsv"
Write-Host "  - P4_by_ticker.tsv"
Write-Host ""
Write-Host "Log completo en: $logFile"
Write-Host "==========================================" 