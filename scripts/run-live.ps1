$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8'

$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$logFile = "logs/live_session_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"

if (-not (Test-Path logs)) { New-Item -ItemType Directory -Path logs | Out-Null }

Write-Host "[$timestamp] Starting live trading session..." -ForegroundColor Green
Write-Host "[$timestamp] Log: $logFile" -ForegroundColor Cyan
Write-Host "[$timestamp] HTTP server: port 9090 (application.yml)" -ForegroundColor Cyan
Write-Host "[$timestamp] DevTools: disabled" -ForegroundColor Yellow

.\gradlew.bat bootRun '--args=--spring.devtools.restart.enabled=false' 2>&1 | Tee-Object -FilePath $logFile
