# Session: Live Feed "Stuck Scanning" Fix
**Date:** 2026-04-15

## Problem
When the app is stopped/killed mid-scan and restarted, the live feed UI shows stale "SCANNING" entries and inconsistent state (progress bar moving but status says "Idle").

## Root Cause (3 Issues)

### 1. `MarketScanner.onStartup()` bypasses LiveModeController state
The startup scan called `scannerService.scanAll()` directly without setting `isScanning = true` in LiveModeController. This caused:
- Scan activity callbacks populated the feed with SCANNING entries
- But `/status` returned `isScanning: false` → UI showed "Idle"
- Progress bar updated via scanner counters but Start Scan button was visible

### 2. `StrategyScannerService` progress counters not reset on startup
The scanner's `scannedCount`, `totalToScan`, `currentBatchLabel` were not explicitly cleared when LiveModeController initialized, potentially showing stale values from a previous crashed session.

### 3. Frontend didn't clean up stale SCANNING entries
When `isScanning` was false, any "SCANNING" status entries in the feed were displayed as-is, with no indication they were from an interrupted session.

## Changes Made

### Backend

#### `MarketScanner.java` - `onStartup()`
- Now calls `liveModeController.updateScanningState(true, ...)` before the startup scan
- Calls `liveModeController.updateScanComplete(elapsed)` after scan finishes
- Adds discovered signals to LiveModeController via `addLiveSignal()`
- Uses try/catch to ensure `updateScanComplete(0)` is called even on failure

#### `LiveModeController.java` - Constructor
- Added `scannerService.resetProgress()` call to clear stale scanner counters on startup

#### `LiveModeController.java` - `updateScanComplete()`
- Now also resets `stopScanRequested` to false for clean state

#### `LiveModeController.java` - `/scan-activity` endpoint
- Added `lastScanTime` to response for frontend completion detection

#### `StrategyScannerService.java` - New `resetProgress()` method
- Resets `scannedCount`, `currentBatchSize`, `currentBatchLabel`, `totalToScan` to zero/empty

### Frontend

#### `LiveDashboard.jsx` - Scan activity polling
- When `isScanning` is false, any remaining "SCANNING" entries are re-mapped to "STALE" status with detail "Interrupted (app restarted)"
- STALE entries use the error badge styling (red) to clearly indicate they were interrupted

## Files Modified
- `src/main/java/com/fgiaquinta/optionsquant/service/MarketScanner.java`
- `src/main/java/com/fgiaquinta/optionsquant/controller/LiveModeController.java`
- `src/main/java/com/fgiaquinta/optionsquant/service/StrategyScannerService.java`
- `frontend/src/pages/LiveDashboard.jsx`

## How to Verify
1. Start the app during market hours → startup scan should show as "Scanning" in UI
2. Kill the app mid-scan (Ctrl+C or close)
3. Restart the app → UI should show clean "Idle" state, then transition to "Scanning" for startup scan
4. Any stale SCANNING entries from the previous session should not appear (constructor clears them)
5. If the startup scan is interrupted again, any remaining SCANNING entries display as "STALE" in red

## TODO if Something Fails
- If the UI still shows stale state, check that `LiveModeController` constructor runs before `MarketScanner.onStartup()` (Spring guarantees this since the constructor runs during bean creation, before `@EventListener`)
- If the startup scan doesn't show in the UI, verify the `liveModeController` bean is injected correctly in `MarketScanner`
- If "Start Scan" doesn't work during startup scan, it's by design (the startup scan sets `isScanning = true`, so manual scan is blocked until it completes; use the existing `/force-stop` endpoint if needed)
- If the frontend shows STALE entries that shouldn't be there, check that the `/scan-activity` response `isScanning` field matches the actual state

Now I can see all three issues clearly. Let me analyze the logs and screenshots:
1. Stop scan is slow - The stop flag is checked before submitting futures, but already-submitted future.get() calls block waiting for 120s IBKR download timeouts. The stop doesn't cancel in-flight futures.
2. Live feed shows "SCANNING" badges glowing even after stop - the frontend maps SCANNING entries to STALE only when isScanning is false, but during "Stopping..." the isScanning is still true.
3. UI layout - Live feed and console need to be smaller/side-by-side with dynamic height.