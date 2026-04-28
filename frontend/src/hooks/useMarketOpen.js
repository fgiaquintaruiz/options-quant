import { useState, useEffect } from 'react'
import { checkMarketOpen } from '../utils/liveSignalUtils'

/** Polling interval for market-open status checks, in milliseconds. */
const MARKET_STATUS_POLLING_INTERVAL_MS = 30_000

/**
 * Polls `checkMarketOpen()` every 30 seconds and returns whether the market
 * is currently open. Initializes synchronously from `checkMarketOpen()` so
 * the first render reflects the current state without a loading phase.
 *
 * @returns {boolean} `true` when the market is open, `false` otherwise.
 */
export function useMarketOpen() {
  const [open, setOpen] = useState(() => checkMarketOpen())
  useEffect(() => {
    const id = setInterval(() => setOpen(checkMarketOpen()), MARKET_STATUS_POLLING_INTERVAL_MS)
    return () => clearInterval(id)
  }, [])
  return open
}
