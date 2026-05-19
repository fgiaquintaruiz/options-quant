import { useState, useEffect } from 'react'
import { replayApi } from '../api'

export function useReplayStatus({ active, intervalMs = 5000 } = {}) {
  const [status, setStatus] = useState(null)
  const [error,  setError]  = useState(null)

  useEffect(() => {
    if (!active) return
    let cancelled = false

    const tick = async () => {
      try {
        const data = await replayApi.status()
        if (cancelled) return
        setStatus({
          active:              !!data?.active,
          virtualNow:          data?.virtualNow ?? null,
          speed:               data?.speed ?? null,
          runId:               data?.runId ?? null,
          replaySignalsCount:  data?.replaySignalsCount ?? null,
        })
        setError(null)
      } catch (e) {
        if (!cancelled) setError(e.message)
      }
    }

    tick()
    const id = setInterval(tick, intervalMs)
    return () => { cancelled = true; clearInterval(id) }
  }, [active, intervalMs])

  return { status, error }
}
