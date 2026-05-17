# Wrapper para correr backtest masivo con DevTools desactivado.
# DevTools restart cierra HikariCP mid-run, rompiendo
# persistencia de callbacks daemon.

param(
    [switch]$Fresh,
    [switch]$Resume,
    [switch]$CompleteTickers = $true
)

$argsList = @("--backtest-all", "--server.port=-1", "--spring.devtools.restart.enabled=false")
if ($CompleteTickers) { $argsList += "--complete-tickers" }
if ($Fresh) { $argsList += "--backtest-fresh" }
if ($Resume) { $argsList += "--backtest-resume" }

$argsString = $argsList -join " "
Write-Host "Corriendo: ./gradlew bootRun --args=`"$argsString`""

& ./gradlew bootRun --args="$argsString"
