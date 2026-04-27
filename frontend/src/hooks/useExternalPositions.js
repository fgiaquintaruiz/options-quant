import { useState, useEffect, useCallback } from 'react'
import { externalPositionsApi } from '../api'

/**
 * Polls GET /live-ui/external-positions every 10 seconds.
 * On error: surfaces error state but keeps last successful positions
 * (stale data is better than a blank panel).
 *
 * Returns: { positions, error, loading, refresh }
 *   - refresh() — triggers an immediate fetch outside the interval (use after a close action)
 */
export function useExternalPositions() {
  const [positions, setPositions] = useState([])
  const [error, setError]         = useState(null)
  const [loading, setLoading]     = useState(true)

  const fetchPositions = useCallback(async () => {
    try {
      const data = await externalPositionsApi.getExternalPositions()
      setPositions(data.positions ?? [])
      setError(null)
    } catch (err) {
      setError(err)
      // Keep previous positions — stale data better than blank
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchPositions()
    const intervalId = setInterval(fetchPositions, 10_000)
    return () => clearInterval(intervalId)
  }, [fetchPositions])

  return { positions, error, loading, refresh: fetchPositions }
}
