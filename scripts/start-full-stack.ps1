<#
.SYNOPSIS
    Levanta el stack completo: Spring Boot (9090), FastAPI analytics (8001), Streamlit (8501).

.DESCRIPTION
    Opcionalmente ejecuta buildFrontend. Abre una ventana de consola por servicio para ver logs.
    Requisitos: Java/Gradle, Node (para build), Python 3.11+ con dependencias en python/requirements.txt.

.PARAMETER SkipFrontendBuild
    No ejecuta gradlew buildFrontend (usar si ya construiste o si llamás desde gradlew startFullStack).

.PARAMETER NoPython
    Solo arranca Spring Boot (mismo efecto práctico que bootRun en ventana nueva).

.EXAMPLE
    .\scripts\start-full-stack.ps1

.EXAMPLE
    .\gradlew.bat startFullStack
#>
param(
    [switch] $SkipFrontendBuild,
    [switch] $NoPython
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Wait-HttpOk {
    param([string] $Url, [int] $MaxSeconds = 120)
    $deadline = (Get-Date).AddSeconds($MaxSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $false
}

$gradlew = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Error "No se encontró gradlew.bat en $repoRoot"
}

if (-not $SkipFrontendBuild) {
    Write-Host ">> buildFrontend..." -ForegroundColor Cyan
    & $gradlew "buildFrontend" --no-daemon
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ">> Iniciando Spring Boot en nueva ventana (puerto 9090)..." -ForegroundColor Cyan
$bootCmd = "Set-Location '$repoRoot'; & '$gradlew' bootRun --no-daemon"
Start-Process powershell -WorkingDirectory $repoRoot -ArgumentList @("-NoExit", "-NoProfile", "-Command", $bootCmd)

if (-not (Wait-HttpOk "http://127.0.0.1:9090/actuator/health")) {
    Write-Error "Timeout esperando a Spring Boot en :9090. Revisá la ventana de bootRun."
}

if ($NoPython) {
    Write-Host ""
    Write-Host "Listo (solo Java):" -ForegroundColor Green
    Write-Host "  App + React:  http://127.0.0.1:9090/"
    exit 0
}

$pyDir = Join-Path $repoRoot "python"
$req = Join-Path $pyDir "requirements.txt"
if (-not (Test-Path $req)) {
    Write-Error "No se encontró python/requirements.txt"
}

$pyCmd = Get-Command python -ErrorAction SilentlyContinue
if (-not $pyCmd) {
    $pyCmd = Get-Command py -ErrorAction SilentlyContinue
}
if (-not $pyCmd) {
    Write-Error "No se encontró `python` ni el launcher `py` en PATH. Instalá Python 3.11+ desde python.org y marcá Add to PATH, o usá `py` en Windows."
}
$pyExe = $pyCmd.Source

Write-Host ">> Iniciando Analytics (FastAPI :8001)..." -ForegroundColor Cyan
$analyticsEnv = "`$env:JAVA_API_HOST='127.0.0.1'; `$env:JAVA_API_PORT='9090'; Set-Location '$pyDir'; & '$pyExe' -m uvicorn analytics_service.main:app --host 127.0.0.1 --port 8001"
Start-Process powershell -WorkingDirectory $pyDir -ArgumentList @("-NoExit", "-NoProfile", "-Command", $analyticsEnv)

if (-not (Wait-HttpOk "http://127.0.0.1:8001/health" 90)) {
    Write-Warning "Analytics no respondió en :8001 a tiempo. Revisá dependencias: py -m pip install -r python/requirements.txt --pre"
}

Write-Host ">> Iniciando Streamlit (:8501)..." -ForegroundColor Cyan
$uiEnv = @"
`$env:API_HOST='127.0.0.1'
`$env:API_PORT='8001'
`$env:JAVA_API_URL='http://127.0.0.1:9090'
Set-Location '$pyDir'
& '$pyExe' -m streamlit run ui_service/main.py --server.port 8501 --server.address 127.0.0.1
"@
Start-Process powershell -WorkingDirectory $pyDir -ArgumentList @("-NoExit", "-NoProfile", "-Command", $uiEnv)

Write-Host ""
Write-Host "Stack arrancado (3 ventanas):" -ForegroundColor Green
Write-Host "  Spring + React:  http://127.0.0.1:9090/"
Write-Host "  Analytics API:   http://127.0.0.1:8001/docs"
Write-Host "  Streamlit UI:    http://127.0.0.1:8501/"
Write-Host ""
Write-Host "Cerrá cada ventana para detener ese proceso." -ForegroundColor DarkGray
