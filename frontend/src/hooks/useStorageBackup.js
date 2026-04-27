import { useEffect, useRef } from 'react'
import { LS } from '../utils/storage'

const BACKUP_KEYS = [
  'ticker_groups',
  'live_maxConcurrent', 'live_tickerFilter', 'live_tickerScope', 'live_riskPct', 'live_mockMarketOpen',
  'bt_maxConcurrent', 'bt_tickerFilter', 'bt_tickerScope', 'bt_params', 'bt_schedulerEnabled',
]

export function useStorageBackup() {
  const timerRef = useRef(null)

  useEffect(() => {
    const trigger = () => {
      clearTimeout(timerRef.current)
      timerRef.current = setTimeout(async () => {
        const snapshot = Object.fromEntries(
          BACKUP_KEYS.map(k => [k, LS.get(k, null)])
        )
        snapshot.savedAt = new Date().toISOString()
        try {
          await fetch('/api/backup', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(snapshot),
          })
        } catch (e) {
          console.warn('Backup failed', e)
        }
      }, 2000)
    }

    window.addEventListener('app-ls-change', trigger)
    window.addEventListener('storage', trigger)
    return () => {
      window.removeEventListener('app-ls-change', trigger)
      window.removeEventListener('storage', trigger)
      clearTimeout(timerRef.current)
    }
  }, [])
}
