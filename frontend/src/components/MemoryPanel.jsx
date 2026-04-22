/**
 * MemoryPanel — collapsible table of persisted TP/SL ATR multiplier overrides.
 *
 * Props:
 *   open      — whether the <details> element is expanded
 *   rows      — array of { ticker, strategy, tpAtrMultOverride, slAtrMultOverride, lastUpdated }
 *   loading   — true while fetching
 *   error     — error message string or null
 *   onToggle  — called with boolean when details opens or closes
 *   onError   — called with error message string after a failed delete
 *   onReload  — async function that refreshes rows from the API
 *
 * Note: UI labels mix Spanish and English intentionally (Spanish for operational context,
 * English for technical/financial terms) — consistent with the rest of the app.
 */
import React, { useCallback } from 'react'
import { backtestApi } from '../api'

export default function MemoryPanel({ open, rows = [], loading, error, onToggle, onError, onReload }) {
  const handleDelete = useCallback(async (ticker, strategy) => {
    try {
      await backtestApi.deleteTickerMemoryRiskProfile(ticker, strategy)
      await onReload()
    } catch (err) {
      onError(err.message || 'No se pudo borrar')
    }
  }, [onReload, onError])

  return (
    <details
      className="card bt-memory-panel"
      open={open}
      onToggle={(e) => onToggle(e.target.open)}
    >
      <summary className="text-md font-bold bt-memory-summary">
        Memoria TP/SL guardada
      </summary>
      <p className="text-xs color-muted bt-memory-desc">
        Entradas con multiplicadores ATR personalizados (las que afectan el próximo backtest).{' '}
        <strong>Última actualización</strong> es cuando se escribió en disco (promote / grid).
      </p>
      {loading && <p className="text-xs color-muted bt-memory-state">Cargando…</p>}
      {error && (
        <p className="text-xs color-error bt-memory-state" role="alert">{error}</p>
      )}
      {!loading && rows.length === 0 && !error && (
        <p className="text-xs color-muted bt-memory-state">No hay overrides TP/SL en memoria.</p>
      )}
      {rows.length > 0 && (
        <div className="bt-memory-table-wrap">
          <table className="bt-memory-table">
            <thead>
              <tr className="bt-memory-thead-row">
                <th className="bt-memory-th">Ticker</th>
                <th className="bt-memory-th">Estrategia</th>
                <th className="bt-memory-th">TP ×ATR</th>
                <th className="bt-memory-th">SL ×ATR</th>
                <th className="bt-memory-th">Última actualización</th>
                <th className="bt-memory-th" />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.ticker}-${r.strategy}`} className="bt-memory-row">
                  <td className="bt-memory-td">{r.ticker}</td>
                  <td className="bt-memory-td bt-memory-td-mono">{r.strategy}</td>
                  <td className="bt-memory-td">
                    {r.tpAtrMultOverride != null ? Number(r.tpAtrMultOverride).toFixed(3) : '—'}
                  </td>
                  <td className="bt-memory-td">
                    {r.slAtrMultOverride != null ? Number(r.slAtrMultOverride).toFixed(3) : '—'}
                  </td>
                  <td className="bt-memory-td text-xs color-muted">
                    {r.lastUpdated != null ? new Date(Number(r.lastUpdated)).toLocaleString() : '—'}
                  </td>
                  <td className="bt-memory-td-action">
                    <button
                      type="button"
                      className="btn btn-secondary bt-memory-del-btn"
                      onClick={() => handleDelete(r.ticker, r.strategy)}
                    >
                      Quitar
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </details>
  )
}
