$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8'

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logsDir = Join-Path $PSScriptRoot ".." "logs"
if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }
$scriptName = [System.IO.Path]::GetFileNameWithoutExtension($MyInvocation.MyCommand.Name)
$logFile = Join-Path $logsDir "${scriptName}_${timestamp}.log"

Write-Host "Log: $logFile"
Write-Host "Follow: Get-Content '$logFile' -Tail 50 -Wait"
Write-Host ""

$startTs = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host "[$startTs] Starting live trading session..." -ForegroundColor Green
Write-Host "[$startTs] HTTP server: port 9090 (application.yml)" -ForegroundColor Cyan
Write-Host "[$startTs] DevTools: disabled" -ForegroundColor Yellow

# Ejecutar linea a linea: cada linea se escribe de inmediato, sobrevive Ctrl+C
.\gradlew.bat bootRun '--args=--spring.devtools.restart.enabled=false' 2>&1 | ForEach-Object {
    $_ | Out-File -FilePath $logFile -Append -Encoding utf8
    Write-Host $_
}

Write-Host ""
Write-Host "Log completo: $logFile"
