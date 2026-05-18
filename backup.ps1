#Requires -Version 5.1
<#
.SYNOPSIS
    SQLite snapshot of candles.db to data\snapshot\ for Google Drive sync.

.NOTES
    Log file    : $UserRoot\backup-log.txt  (resolved at runtime per OS)
    Run by Windows Task Scheduler (daily at 08:00)
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $UserRoot   = $env:USERPROFILE
    $GeminiConf = Join-Path $env:APPDATA 'gemini'
    $Sep        = '\'
} elseif ($IsMacOS) {
    $UserRoot   = $env:HOME
    $GeminiConf = Join-Path $env:HOME 'Library/Application Support/gemini'
    $Sep        = '/'
} else {
    $UserRoot   = $env:HOME
    $GeminiConf = Join-Path $env:HOME '.config/gemini'
    $Sep        = '/'
}

$LogFile  = Join-Path $UserRoot 'backup-log.txt'
$MaxLogMB = 10

# ---------------------------------------------------------------------------
# LOGGING
# ---------------------------------------------------------------------------
function Write-Log {
    param(
        [string]$Message,
        [string]$Level = 'INFO'
    )
    $ts   = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    $line = "[$ts] [$Level] $Message"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Rotate-Log {
    if (Test-Path $LogFile) {
        $sizeMB = (Get-Item $LogFile).Length / 1MB
        if ($sizeMB -ge $MaxLogMB) {
            $stamp   = (Get-Date).ToString('yyyyMMdd-HHmmss')
            $archive = $LogFile -replace '\.txt$', "-$stamp.txt"
            Move-Item $LogFile $archive
            Write-Log "Log rotated to: $archive"
        }
    }
}

# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------
Rotate-Log
Write-Log '==============================='
Write-Log "Backup started"
Write-Log '==============================='

$errors  = 0
$dataDir = "$UserRoot\IdeaProjects\options-quant\data"

# ── TRADING DATA ─────────────────────────────────────────────────────────────
Write-Log '--- TRADING DATA ---'

$snapshotDir   = "$dataDir\snapshot"
$candlesBackup = "$snapshotDir\candles.db"
New-Item -ItemType Directory -Force -Path $snapshotDir | Out-Null
Write-Log "Creating SQLite snapshot of candles.db"
$sqliteProc = Start-Process -FilePath "sqlite3" -ArgumentList """$dataDir\candles.db"" "".backup $candlesBackup""" -PassThru -NoNewWindow -RedirectStandardError "$env:TEMP\sqlite3-err.txt"
$sqliteProc.WaitForExit()
if ($sqliteProc.ExitCode -eq 0) {
    Write-Log "OK: candles.db snapshot saved to $candlesBackup"
} else {
    Write-Log "sqlite3 backup failed (exit $($sqliteProc.ExitCode)) — skipping candles.db" 'ERROR'
    $errors++
}

# ── SUMMARY ──────────────────────────────────────────────────────────────────
Write-Log '==============================='
if ($errors -gt 0) {
    Write-Log "Backup FINISHED WITH $errors ERROR(S) -- check log above" 'ERROR'
    exit 1
}
Write-Log 'Backup COMPLETED SUCCESSFULLY'
exit 0
