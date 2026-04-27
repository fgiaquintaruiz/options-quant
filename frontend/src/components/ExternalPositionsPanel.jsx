import React, { useState } from 'react'
import { useExternalPositions } from '../hooks/useExternalPositions'
import { externalPositionsApi } from '../api'

/**
 * Displays external TWS positions (held outside the scanner's bracketStateMap).
 * Self-contained: owns its own polling via useExternalPositions hook.
 * Actions per row: immediate market close, and scheduled 14:50 ET close.
 */
export default function ExternalPositionsPanel() {
  const { positions, error, loading, refresh } = useExternalPositions()

  // UI state: pending close/schedule actions per ticker (prevents double-click)
  const [closing, setClosing]           = useState(() => new Set())
  const [scheduled1450, setScheduled]   = useState(() => new Set())
  const [rowErrors, setRowErrors]       = useState({})

  const setRowError = (ticker, msg) =>
    setRowErrors((prev) => ({ ...prev, [ticker]: msg }))

  const clearRowError = (ticker) =>
    setRowErrors((prev) => { const n = { ...prev }; delete n[ticker]; return n })

  // ── Close action ──────────────────────────────────────────────────────────

  const handleClose = async (ticker) => {
    const ok = window.confirm(`Cerrar posición ${ticker} a mercado?`)
    if (!ok) return

    clearRowError(ticker)
    setClosing((prev) => new Set(prev).add(ticker))
    try {
      await externalPositionsApi.closeExternalPosition(ticker)
      await refresh()
    } catch (err) {
      setRowError(ticker, err.message)
    } finally {
      setClosing((prev) => {
        const n = new Set(prev)
        n.delete(ticker)
        return n
      })
    }
  }

  // ── Schedule 14:50 ET close ───────────────────────────────────────────────

  const handleSchedule1450 = async (ticker) => {
    clearRowError(ticker)
    setScheduled((prev) => new Set(prev).add(ticker))
    try {
      await externalPositionsApi.scheduleClose1450(ticker)
    } catch (err) {
      // On error: remove from scheduled so user can retry
      setScheduled((prev) => {
        const n = new Set(prev)
        n.delete(ticker)
        return n
      })
      setRowError(ticker, err.message)
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="card external-positions-panel" data-testid="external-positions-panel">
      <div className="flex-between mb-12">
        <h3 className="m-0">Posiciones Externas (TWS)</h3>
        {loading && <span className="text-xs color-muted">Actualizando…</span>}
      </div>

      {error && (
        <div className="error-banner mb-12" data-testid="external-positions-error">
          <span>{error.message}</span>
        </div>
      )}

      {positions.length === 0 ? (
        <p className="color-muted text-sm" data-testid="external-positions-empty">
          Sin posiciones externas
        </p>
      ) : (
        <div className="table-wrap">
          <table className="w-full external-positions-table">
            <thead>
              <tr className="bg-card">
                <th className="ltg-th">Ticker</th>
                <th className="ltg-th">Tipo</th>
                <th className="ltg-th">Cantidad</th>
                <th className="ltg-th">Costo prom.</th>
                <th className="ltg-th">Snapshot</th>
                <th className="ltg-th ltg-th--center">Acciones</th>
              </tr>
            </thead>
            <tbody>
              {positions.map((pos) => {
                const isClosing   = closing.has(pos.ticker)
                const isScheduled = scheduled1450.has(pos.ticker)
                const rowError    = rowErrors[pos.ticker]

                return (
                  <tr key={pos.ticker} className="ltg-row-normal">
                    <td className="ltg-td">
                      <div className="flex-align-center gap-6">
                        <strong className="ltg-ticker-label">{pos.ticker}</strong>
                        <span className="badge badge-external">EXTERNAL</span>
                      </div>
                      {rowError && (
                        <div className="text-xs color-error mt-2">{rowError}</div>
                      )}
                    </td>
                    <td className="ltg-td text-sm">{pos.secType}</td>
                    <td className="ltg-td text-sm">{pos.quantity}</td>
                    <td className="ltg-td text-sm">{Number(pos.avgCost).toFixed(2)}</td>
                    <td className="ltg-td text-xs color-muted">{pos.snapshotAt || '—'}</td>
                    <td className="ltg-td--center">
                      <div className="flex-center gap-8">

                        {/* Close button */}
                        <button
                          type="button"
                          className="btn text-xs ltg-action-btn"
                          data-testid={`close-btn-${pos.ticker}`}
                          disabled={isClosing}
                          onClick={() => handleClose(pos.ticker)}
                          style={{
                            background: '#f8514922',
                            border: '1px solid #f8514966',
                            color: isClosing ? '#8b949e' : '#f85149',
                          }}
                        >
                          {isClosing ? 'Cerrando…' : 'Close'}
                        </button>

                        {/* 14:50 ET schedule checkbox / badge */}
                        {isScheduled ? (
                          <span className="badge badge-scheduled-1450" data-testid={`schedule-1450-badge-${pos.ticker}`}>
                            14:50 ET
                          </span>
                        ) : (
                          <label className="flex-align-center gap-4 text-xs color-muted" style={{ cursor: 'pointer' }}>
                            <input
                              type="checkbox"
                              data-testid={`schedule-1450-${pos.ticker}`}
                              disabled={isScheduled}
                              onChange={() => handleSchedule1450(pos.ticker)}
                              style={{ cursor: 'pointer' }}
                            />
                            14:50 ET
                          </label>
                        )}

                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
