/**
 * BacktestHistoryPage — browse and inspect historical backtest runs.
 *
 * Layout:
 *   RunsList (left / top) — click to select a run
 *   RunDetailPanel (right / bottom) — shown only after a run is selected
 *
 * Reads ?runId= from URL query params to pre-select a run on load.
 */
import React, { useState, useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { backtestHistoryApi } from '../api'
import RunsList from '../components/RunsList'
import RunDetailPanel from '../components/RunDetailPanel'

export default function BacktestHistoryPage() {
  const [runs, setRuns]               = useState([])
  const [loading, setLoading]         = useState(false)
  const [error, setError]             = useState(null)
  const [selectedRunId, setSelectedRunId] = useState(null)

  // Best-effort: pre-select run from ?runId= query param
  const [searchParams, setSearchParams] = useSearchParams()

  useEffect(() => {
    const qRunId = searchParams.get('runId')
    if (qRunId) setSelectedRunId(qRunId)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    setLoading(true)
    setError(null)
    backtestHistoryApi.listRuns()
      .then((data) => setRuns(Array.isArray(data) ? data : []))
      .catch((e) => setError(e.message || 'Error loading runs'))
      .finally(() => setLoading(false))
  }, [])

  const handleSelect = (runId) => {
    setSelectedRunId(runId)
    setSearchParams({ runId }, { replace: true })
  }

  if (loading) {
    return (
      <div style={{ padding: 16 }}>
        <p className="color-muted text-xs">Loading backtest runs…</p>
      </div>
    )
  }

  if (error) {
    return (
      <div style={{ padding: 16 }}>
        <p className="color-error text-xs">Error: {error}</p>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap', padding: '8px 0' }}>
      {/* Left panel — run list */}
      <div style={{ flex: '0 0 340px', minWidth: 280 }}>
        <RunsList
          runs={runs}
          selectedRunId={selectedRunId}
          onSelect={handleSelect}
        />
      </div>

      {/* Right panel — run detail */}
      {selectedRunId && (
        <div className="card" style={{ flex: '1 1 600px', padding: 16, minWidth: 320 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <h3 style={{ margin: 0, fontSize: 14 }}>Run: <code style={{ fontSize: 13 }}>{selectedRunId}</code></h3>
            <button
              onClick={() => setSelectedRunId(null)}
              style={{ fontSize: 11, padding: '2px 8px', cursor: 'pointer' }}
            >
              ✕ Close
            </button>
          </div>
          <RunDetailPanel runId={selectedRunId} />
        </div>
      )}

      {!selectedRunId && runs.length > 0 && (
        <div style={{ flex: '1 1 400px', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 32 }}>
          <p className="color-muted text-xs">Select a run from the list to view details.</p>
        </div>
      )}
    </div>
  )
}
