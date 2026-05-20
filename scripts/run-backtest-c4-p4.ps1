# Wrapper para backtest C4/P4 Opening con entry window 9:30-9:36 ET
# Invoca C4P4OpeningBacktestIT directamente (unica forma de override entry window)
# Resultados en analysis/backtest_c4_p4_opening_<FECHA>/
#
# IMPORTANTE: C4P4OpeningBacktestIT escribe sus outputs directamente al directorio
# analysis/backtest_c4_p4_opening_<FECHA>/ — este script NO copia archivos post-run.
#
# Uso: .\scripts\run-backtest-c4-p4.ps1

$ErrorActionPreference = "Stop"

$date = Get-Date -Format "yyyy-MM-dd"
$outputDir = "analysis/backtest_c4_p4_opening_$date"

Write-Host "=========================================="
Write-Host "  Backtest C4/P4 Opening — $date"
Write-Host "  Entry window: 9:30-9:36 AM ET"
Write-Host "  Tickers: 16 (HOT + Tactical)"
Write-Host "  Output: $outputDir"
Write-Host "=========================================="
Write-Host ""

Write-Host "Corriendo: ./gradlew slowTest --tests `"*C4P4OpeningBacktestIT*`""
Write-Host ""

& ./gradlew slowTest --tests "*C4P4OpeningBacktestIT*"

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Error "Gradle fallo con codigo $LASTEXITCODE. Revisa el output de arriba."
    exit $LASTEXITCODE
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
Write-Host "=========================================="
