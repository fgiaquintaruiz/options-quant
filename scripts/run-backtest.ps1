# Wrapper para correr backtest masivo con DevTools desactivado.
# DevTools restart cierra HikariCP mid-run, rompiendo
# persistencia de callbacks daemon.
#
# Examples:
#   .\scripts\run-backtest.ps1                        # Run all strategies
#   .\scripts\run-backtest.ps1 -Strategies c4,p4      # Run only C4 and P4
#   .\scripts\run-backtest.ps1 -Fresh -Strategies p1  # Fresh run, P1 only
param(
    [switch]$Fresh,
    [switch]$Resume,
    [switch]$CompleteTickers = $true,
    [string]$Strategies  # Optional filter: comma-separated codes e.g. "c4,p4"
)
$argsList = @("--backtest-all", "--server.port=-1", "--spring.devtools.restart.enabled=false")
if ($CompleteTickers) { $argsList += "--complete-tickers" }
if ($Fresh) { $argsList += "--backtest-fresh" }
if ($Resume) { $argsList += "--backtest-resume" }
if ($Strategies) { $argsList += "--strategies=$Strategies" }
$argsString = $argsList -join " "

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logsDir = Join-Path $PSScriptRoot ".." "logs"
if (-not (Test-Path $logsDir)) {
    New-Item -ItemType Directory -Path $logsDir | Out-Null
}
$scriptName = [System.IO.Path]::GetFileNameWithoutExtension($MyInvocation.MyCommand.Name)
$logFile = Join-Path $logsDir "${scriptName}_${timestamp}.log"

Write-Host "Log: $logFile"
Write-Host "Follow: Get-Content '$logFile' -Tail 50 -Wait"
Write-Host ""

Write-Host "Corriendo: ./gradlew bootRun --args=`"$argsString`""

# Ejecutar linea a linea: cada linea se escribe de inmediato, sobrevive Ctrl+C
& ./gradlew bootRun --args="$argsString" 2>&1 | ForEach-Object {
    $_ | Out-File -FilePath $logFile -Append -Encoding utf8
    Write-Host $_
}

Write-Host ""
Write-Host "Log completo: $logFile"