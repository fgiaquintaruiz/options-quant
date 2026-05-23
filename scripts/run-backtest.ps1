# Wrapper para correr backtest masivo con DevTools desactivado.
# DevTools restart cierra HikariCP mid-run, rompiendo
# persistencia de callbacks daemon.
#
# Examples:
#   .\scripts\run-backtest.ps1                                       # Run all strategies, 2018→today
#   .\scripts\run-backtest.ps1 -Strategies c4,p4                     # Run only C4 and P4
#   .\scripts\run-backtest.ps1 -Fresh -Strategies p1                 # Fresh run, P1 only
#   .\scripts\run-backtest.ps1 -StartDate 2024-01-01 -EndDate 2024-12-31  # Window 2024
param(
    [switch]$Fresh,
    [switch]$Force,
    [switch]$Resume,
    [switch]$CompleteTickers = $true,
    [string]$Strategies, # Optional filter: comma-separated codes e.g. "c4,p4"
    [string]$StartDate,  # Optional ISO start date: YYYY-MM-DD
    [string]$EndDate     # Optional ISO end date: YYYY-MM-DD
)

# Fail fast on bad ISO format — beats waiting 30s for gradle to start before erroring out.
function Test-IsoDate {
    param([string]$value, [string]$flagName)
    if (-not $value) { return }
    try {
        [datetime]::ParseExact($value, 'yyyy-MM-dd', $null) | Out-Null
    } catch {
        throw "Invalid -$flagName value '$value'. Expected ISO format YYYY-MM-DD."
    }
}
Test-IsoDate -value $StartDate -flagName 'StartDate'
Test-IsoDate -value $EndDate -flagName 'EndDate'

$argsList = @("--backtest-all", "--server.port=-1", "--spring.devtools.restart.enabled=false")
if ($CompleteTickers) { $argsList += "--complete-tickers" }
if ($Resume) { $argsList += "--backtest-resume" }
# Spring expects --strategies=c4,p4 (comma-separated, no spaces)
if ($Strategies) { $argsList += "--strategies=$(($Strategies -split '[,\s]+' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ',')" }
if ($StartDate) { $argsList += "--start-date=$StartDate" }
if ($EndDate) { $argsList += "--end-date=$EndDate" }
$argsString = $argsList -join " "

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logsDir = Join-Path $PSScriptRoot ".." "logs"
if (-not (Test-Path $logsDir)) {
    New-Item -ItemType Directory -Path $logsDir | Out-Null
}
$scriptName = [System.IO.Path]::GetFileNameWithoutExtension($MyInvocation.MyCommand.Name)
$logFile = Join-Path $logsDir "${scriptName}_${timestamp}.log"

function Write-AndLog {
    param([string]$msg)
    Write-Host $msg
    $msg | Out-File -FilePath $logFile -Append -Encoding utf8
}

Write-Host "Log: $logFile"
Write-Host "Follow: Get-Content '$logFile' -Tail 50 -Wait"
Write-Host ""

if ($Fresh) {
    if ($Force) {
        Write-AndLog "Force flag detected - skipping confirmation"
    } else {
        $promptText = "About to delete all backtest data. Continue? (y/n)"
        Write-AndLog $promptText
        $answer = Read-Host $promptText
        Write-AndLog "User answer: $answer"
        if ($answer -ine 'y') {
            Write-AndLog "Aborted"
            exit 1
        }
    }
    $argsList += "--backtest-fresh-force"
    $argsString = $argsList -join " "
}

Write-AndLog "Corriendo: ./gradlew bootRun --args=`"$argsString`""

# Ejecutar linea a linea: cada linea se escribe de inmediato, sobrevive Ctrl+C
& ./gradlew bootRun --args="$argsString" 2>&1 | ForEach-Object {
    $_ | Out-File -FilePath $logFile -Append -Encoding utf8
    Write-Host $_
}

Write-Host ""
Write-Host "Log completo: $logFile"