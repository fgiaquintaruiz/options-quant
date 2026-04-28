import { useState, useEffect } from 'react'

/** Polling interval for the next-scan countdown tick, in milliseconds. */
const COUNTDOWN_TICK_MS = 1_000

/** Polling interval for the elapsed-scan timer tick, in milliseconds. */
const ELAPSED_TICK_MS = 1_000

// Tracks two scan-related timers.
// nextScanSecs: countdown to the next quarter-hour scan trigger, updated every second.
// elapsed: seconds since scanning became true; resets to 0 when scanning is false.
export function useScanCountdown({ scanning }) {
  const [nextScanSecs, setNextScanSecs] = useState(null)
  const [elapsed, setElapsed] = useState(0)

  // Countdown to next scheduled scan (every 15 minutes on the quarter-hour)
  useEffect(() => {
    const tick = () => {
      const now = new Date()
      let rem = (15 - (now.getMinutes() % 15)) * 60 - now.getSeconds()
      setNextScanSecs(rem <= 0 ? 15 * 60 : rem)
    }
    tick()
    const id = setInterval(tick, COUNTDOWN_TICK_MS)
    return () => clearInterval(id)
  }, [])

  // Elapsed scan timer
  useEffect(() => {
    if (!scanning) { setElapsed(0); return }
    const start = Date.now()
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), ELAPSED_TICK_MS)
    return () => clearInterval(id)
  }, [scanning])

  return { nextScanSecs, elapsed }
}
