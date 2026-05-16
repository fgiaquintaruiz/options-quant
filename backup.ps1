#Requires -Version 5.1
<#
.SYNOPSIS
    Automated backup to Google Drive via rclone.
    CRITICAL paths: daily. HIGH + CONFIG + UNKNOWN paths: weekly (Sunday).

.NOTES
    Destination : gdrive:DevBackup/FGIAQUINTA/
    Log file    : $UserRoot\backup-log.txt  (resolved at runtime per OS)
    Run by Windows Task Scheduler (daily at 08:00)
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------
$RcloneBin  = if (Test-Path "$env:USERPROFILE\scoop\apps\rclone\current\rclone.exe") {
    "$env:USERPROFILE\scoop\apps\rclone\current\rclone.exe"
} else { 'rclone' }
$GDriveRoot = 'gdrive:DevBackup/FGIAQUINTA'

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

$LogFile    = Join-Path $UserRoot 'backup-log.txt'
$MaxLogMB   = 10
$RcloneConf = if (Test-Path "$env:USERPROFILE\scoop\apps\rclone\current\rclone.conf") {
    "$env:USERPROFILE\scoop\apps\rclone\current\rclone.conf"
} else {
    ((& $RcloneBin config file 2>&1) | Where-Object { $_ -notmatch 'stored at' -and $_.Trim() -ne '' } | Select-Object -First 1).Trim()
}

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
# RCLONE HELPERS
# ---------------------------------------------------------------------------

# Copy a single file with rclone copyto. Returns $true on success.
function Copy-SingleFile {
    param(
        [string]$Src,
        [string]$Dst
    )
    if (-not (Test-Path $Src)) {
        Write-Log "SKIP (not found): $Src" 'WARN'
        return $true
    }
    Write-Log "Copying file: $Src"
    $out  = & $RcloneBin copyto $Src $Dst --retries 3 --log-level ERROR 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $msg = "rclone FAILED [exit $code]: $out"
        Write-Log $msg 'ERROR'
        return $false
    }
    Write-Log "OK: $Src"
    return $true
}

# Copy a directory with optional extra rclone flags. Returns $true on success.
function Copy-Directory {
    param(
        [string]   $Src,
        [string]   $Dst,
        [string[]] $ExtraFlags = @()
    )
    if (-not (Test-Path $Src)) {
        Write-Log "SKIP (not found): $Src" 'WARN'
        return $true
    }
    Write-Log "Copying dir: $Src"
    $flags = @('copy', $Src, $Dst,
               '--transfers', '4',
               '--checkers',  '8',
               '--retries',   '3',
               '--low-level-retries', '10',
               '--stats',     '0',
               '--log-level', 'ERROR') + $ExtraFlags
    $out  = & $RcloneBin @flags 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $msg = "rclone FAILED [exit $code]: $out"
        Write-Log $msg 'ERROR'
        return $false
    }
    Write-Log "OK: $Src"
    return $true
}

# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------
Rotate-Log
Write-Log '==============================='
Write-Log "Backup started"
Write-Log '==============================='

$errors = 0
$dataDir    = "$UserRoot\IdeaProjects\options-quant\data"
$dataRemote = "$GDriveRoot/IdeaProjects/options-quant/data"

# ── CRITICAL (always, every run) ────────────────────────────────────────────
Write-Log '--- TRADING DATA ---'

if (-not (Copy-SingleFile "$dataDir\candles.db" "$dataRemote/candles.db")) {
    $errors++
}

if (-not (Copy-Directory $dataDir $dataRemote @('--exclude', 'data/stooq/**', '--include', '*.csv'))) {
    $errors++
}

# ── CONFIG + ENGRAM + GEMINI (daily) ────────────────────────────────────────
Write-Log '--- ENGRAM + CLAUDE ---'

if (-not (Copy-Directory "$UserRoot\.claude\projects" "$GDriveRoot/.claude/projects" @('--ignore-checksum'))) {
    $errors++
}

Write-Log '--- CONFIG ---'

$singleFiles = @(
    [pscustomobject]@{
        Src = $RcloneConf
        Dst = "$GDriveRoot/rclone/rclone.conf"
    },
    [pscustomobject]@{
        Src = "$UserRoot\.claude\CLAUDE.md"
        Dst = "$GDriveRoot/.claude/CLAUDE.md"
    },
    [pscustomobject]@{
        Src = "$UserRoot\.claude\settings.json"
        Dst = "$GDriveRoot/.claude/settings.json"
    },
    [pscustomobject]@{
        Src = "$UserRoot\.gemini\GEMINI.md"
        Dst = "$GDriveRoot/.gemini/GEMINI.md"
    },
    [pscustomobject]@{
        Src = "$UserRoot\.gemini\settings.json"
        Dst = "$GDriveRoot/.gemini/settings.json"
    },
    [pscustomobject]@{
        Src = "$UserRoot\.gemini\projects.json"
        Dst = "$GDriveRoot/.gemini/projects.json"
    },
    [pscustomobject]@{
        Src = (Join-Path $GeminiConf 'system.md')
        Dst = "$GDriveRoot/gemini-conf/system.md"
    },
    [pscustomobject]@{
        Src = (Join-Path $GeminiConf 'settings.json')
        Dst = "$GDriveRoot/gemini-conf/settings.json"
    }
)

foreach ($f in $singleFiles) {
    if (-not (Copy-SingleFile $f.Src $f.Dst)) { $errors++ }
}

if (-not (Copy-Directory "$UserRoot\.claude\skills" "$GDriveRoot/.claude/skills")) {
    $errors++
}

if (-not (Copy-Directory "$UserRoot\.claude\hooks" "$GDriveRoot/.claude/hooks")) {
    $errors++
}

# ── SUMMARY ─────────────────────────────────────────────────────────────────
Write-Log '==============================='
if ($errors -gt 0) {
    Write-Log "Backup FINISHED WITH $errors ERROR(S) -- check log above" 'ERROR'
    exit 1
}
Write-Log 'Backup COMPLETED SUCCESSFULLY'
exit 0
