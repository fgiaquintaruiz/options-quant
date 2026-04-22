<#
.SYNOPSIS
    Muestra un .md en la consola con formato (vía glow), o en texto plano si no está instalado.

.DESCRIPTION
    Requiere "glow" (charmbracelet) para salida con colores y estilos. Instalación:
      scoop install glow
      choco install glow
      winget install charmbracelet.glow
    Más: https://github.com/charmbracelet/glow#installation

.PARAMETER Path
    Ruta al fichero .md. Por defecto: docs/LIVE-MODE-PRD.md

.EXAMPLE
    .\scripts\preview-md.ps1

.EXAMPLE
    .\scripts\preview-md.ps1 .\README.md

.EXAMPLE
    .\scripts\preview-md.ps1 docs\README.md -Pager
#>
param(
    [Parameter(Position = 0)]
    [string] $Path = "",
    [switch] $Pager
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Path)) {
    $Path = Join-Path $repoRoot "docs\LIVE-MODE-PRD.md"
}
$resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
if (-not $resolved) {
    Write-Error "No existe el archivo: $Path"
    exit 1
}

$target = $resolved.Path
$glowCmd = Get-Command glow -ErrorAction SilentlyContinue

if (-not $glowCmd) {
    Write-Host "glow no esta en PATH. Texto sin formato. Instalacion: scoop install glow  /  choco install glow  /  winget install charmbracelet.glow" -ForegroundColor Yellow
    Write-Host "https://github.com/charmbracelet/glow/releases" -ForegroundColor Cyan
    Get-Content -LiteralPath $target -Raw -Encoding UTF8
    exit 0
}

$glowArgs = @($target)
if ($Pager) {
    $glowArgs += "-p"
}
& glow @glowArgs
if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }
exit 0
