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
    $7zBin = "C:\Users\FGIAQUINTA\scoop\shims\7z.exe"
    $zipFile = "$snapshotDir\candles.db.7z"
    Write-Log "Compressing snapshot with 7z..."
    Remove-Item $zipFile -Force -ErrorAction SilentlyContinue
    $7zOut = & $7zBin a -t7z -mx=3 $zipFile $candlesBackup 2>&1
    if ($LASTEXITCODE -eq 0) {
        Remove-Item $candlesBackup -Force
        Write-Log "OK: snapshot compressed to $zipFile"
    } else {
        Write-Log "7z compression failed — keeping uncompressed snapshot. Output: $7zOut" 'WARN'
    }
} else {
    Write-Log "sqlite3 backup failed (exit $($sqliteProc.ExitCode)) — skipping candles.db" 'ERROR'
    $errors++
}

# ── TICKER MEMORY (rolling, últimas 5 versiones) ─────────────────────────────
$tickerMemorySrc = "$dataDir\ticker-memory.json"
if (Test-Path $tickerMemorySrc) {
    $tickerMemoryDir = "$snapshotDir\ticker-memory"
    New-Item -ItemType Directory -Force -Path $tickerMemoryDir | Out-Null
    $tmStamp = (Get-Date).ToString('yyyyMMdd_HHmmss')
    $tickerMemoryDst = "$tickerMemoryDir\ticker-memory-$tmStamp.json"
    Copy-Item -Path $tickerMemorySrc -Destination $tickerMemoryDst
    Write-Log "OK: ticker-memory.json snapshot saved to $tickerMemoryDst"
    $tmFiles = Get-ChildItem -Path $tickerMemoryDir -Filter 'ticker-memory-*.json' |
               Sort-Object LastWriteTime
    if ($tmFiles.Count -gt 5) {
        $toDelete = $tmFiles | Select-Object -First ($tmFiles.Count - 5)
        foreach ($f in $toDelete) {
            Remove-Item $f.FullName -Force
            Write-Log "Rotated old snapshot: $($f.Name)"
        }
    }
} else {
    Write-Log "ticker-memory.json not found — skipping" 'WARN'
}

# ── SETTINGS & RUNTIME FILES (sobrescribir) ───────────────────────────────────
$simpleBackups = @(
    @{ Src = "$dataDir\settings-backup.json"; Dst = "$snapshotDir\settings-backup.json" },
    @{ Src = "$dataDir\ticker-runtime.json";  Dst = "$snapshotDir\ticker-runtime.json"  },
    @{ Src = "$dataDir\candles.sqbpro";       Dst = "$snapshotDir\candles.sqbpro"       }
)
foreach ($item in $simpleBackups) {
    if (Test-Path $item.Src) {
        Copy-Item -Path $item.Src -Destination $item.Dst -Force
        Write-Log "OK: $([System.IO.Path]::GetFileName($item.Src)) backed up to snapshot"
    } else {
        Write-Log "$([System.IO.Path]::GetFileName($item.Src)) not found — skipping" 'WARN'
    }
}

# ── SUMMARY ──────────────────────────────────────────────────────────────────
Write-Log '==============================='
if ($errors -gt 0) {
    Write-Log "Backup FINISHED WITH $errors ERROR(S) -- check log above" 'ERROR'
    exit 1
}
Write-Log 'Backup COMPLETED SUCCESSFULLY'
exit 0
