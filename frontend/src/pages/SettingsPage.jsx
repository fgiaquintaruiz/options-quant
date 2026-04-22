/**
 * SettingsPage — manages ticker universe/HOT configuration (synced to the server)
 * and quick-list groups (persisted in localStorage).
 *
 * Server config is loaded on mount and written via PUT /api/ticker-config.
 * Quick groups are stored in LS under 'ticker_groups' and synced automatically via useEffect.
 *
 * Note: UI labels intentionally mix Spanish and English — consistent with the rest of the app.
 */
import React, { useState, useEffect, useCallback, useMemo } from 'react'
import { Save, Plus, Trash2, List, RefreshCw, Database } from 'lucide-react'
import { LS } from '../utils/storage'
import { tickerConfigApi } from '../api'
import FundamentalTickerForm from '../components/FundamentalTickerForm'

const DEFAULT_GROUPS = [
  { id: 'mega-tech', label: 'Mega Tech', tickers: 'AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,AMD,AVGO,ORCL' },
  { id: 'spy-top-10', label: 'SPY Top 10', tickers: 'AAPL,MSFT,NVDA,AMZN,META,GOOGL,BRK B,GOOG,TSLA,AVGO' },
  { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD,AVGO,INTC,TSM,ASML,QCOM,MU,AMAT,LRCX' },
  { id: 'finance', label: 'Finance', tickers: 'JPM,V,MA,BAC,MS,GS,HSBC,AXP,PYPL,COIN' },
  { id: 'crypto', label: 'Crypto Proxy', tickers: 'COIN,MARA,RIOT,MSTR,CLSK,MHT,WULF,BTBT' },
  { id: 'spy', label: 'SPY', tickers: 'SPY' },
  { id: 'qqq', label: 'QQQ', tickers: 'QQQ' },
]

function parseList(str) {
  if (!str || !String(str).trim()) return []
  return String(str)
    .split(/[\s,]+/)
    .map((s) => s.trim().toUpperCase())
    .filter(Boolean)
}

export default function SettingsPage() {
  const [groups, setGroups] = useState(() => LS.get('ticker_groups', DEFAULT_GROUPS))
  const [newGroup, setNewGroup] = useState({ label: '', tickers: '' })
  const [saveStatus, setSaveStatus] = useState(null)

  const [tcLoading, setTcLoading] = useState(true)
  const [tcError, setTcError] = useState(null)
  const [universeStr, setUniverseStr] = useState('')
  const [hotStr, setHotStr] = useState('')
  const [fundamentals, setFundamentals] = useState({})
  const [hasRuntimeFile, setHasRuntimeFile] = useState(false)
  const [editingSymbol, setEditingSymbol] = useState(null)
  const [newTickerInput, setNewTickerInput] = useState('')

  const loadTickerConfig = useCallback(async () => {
    setTcLoading(true)
    setTcError(null)
    try {
      const data = await tickerConfigApi.get()
      setUniverseStr((data.universe || []).join(', '))
      setHotStr((data.hot || []).join(', '))
      setFundamentals(data.fundamentals || data.entries || {})
      setHasRuntimeFile(!!data.hasRuntimeFile)
    } catch (e) {
      setTcError(e.message || 'No se pudo cargar /api/ticker-config')
    } finally {
      setTcLoading(false)
    }
  }, [])

  useEffect(() => { loadTickerConfig() }, [loadTickerConfig])
  useEffect(() => { LS.set('ticker_groups', groups) }, [groups])

  const handleSaveTickerConfig = async () => {
    setTcError(null)
    setSaveStatus(null)
    try {
      const universe = parseList(universeStr)
      const hot = parseList(hotStr)
      await tickerConfigApi.put({ universe, hot, fundamentals })
      setSaveStatus('Ticker config guardado en data/ticker-runtime.json')
      setTimeout(() => setSaveStatus(null), 4000)
      await loadTickerConfig()
    } catch (e) {
      setTcError(e.message || 'Error al guardar')
    }
  }

  const handleRemoveFromUniverse = async (sym) => {
    setUniverseStr(parseList(universeStr).filter((t) => t !== sym).join(', '))
    setHotStr(parseList(hotStr).filter((t) => t !== sym).join(', '))
    setFundamentals((prev) => {
      const next = { ...prev }
      delete next[sym]
      return next
    })
    if (editingSymbol === sym) setEditingSymbol(null)
  }

  const handleAddTicker = () => {
    const sym = newTickerInput.trim().toUpperCase().replace(/[^A-Z0-9 .]/g, '')
    if (!sym) return
    const u = parseList(universeStr)
    if (u.includes(sym)) { setNewTickerInput(''); return }
    setUniverseStr([...u, sym].join(', '))
    setFundamentals((prev) => ({
      ...prev,
      [sym]: prev[sym] || {
        companyName: sym, sector: null, marketCapBillion: null, peRatio: null,
        dividendYield: null, beta: null, epsGrowth: null, revenueGrowth: null,
        debtToEquity: null, roic: null, notes: null,
      },
    }))
    setNewTickerInput('')
  }

  const handleSaveGroup = () => {
    if (!newGroup.label || !newGroup.tickers) return
    const id = newGroup.label.toLowerCase().replace(/\s+/g, '-')
    setGroups([...groups, { ...newGroup, id }])
    setNewGroup({ label: '', tickers: '' })
  }

  const handleSave = () => {
    // groups are already synced to LS via useEffect; this just shows feedback
    setSaveStatus('Settings saved successfully!')
    setTimeout(() => setSaveStatus(null), 3000)
  }

  // Memoized to avoid regex parsing on every keystroke in the textarea.
  const universeList = useMemo(() => parseList(universeStr), [universeStr])

  return (
    <div className="flex-col gap-20 sp-container">
      <div className="card">
        <div className="flex-between flex-wrap gap-10 mb-12">
          <h3 className="m-0 flex-align-center gap-8">
            <Database size={20} className="color-info" />
            Universo de tickers y HOT (servidor)
          </h3>
          <div className="flex-align-center gap-8">
            <button type="button" className="btn btn-secondary" onClick={loadTickerConfig} disabled={tcLoading}>
              <RefreshCw size={16} /> Recargar
            </button>
            <button type="button" className="btn btn-primary" onClick={handleSaveTickerConfig} disabled={tcLoading || !!tcError}>
              <Save size={16} /> Guardar en data/ticker-runtime.json
            </button>
          </div>
        </div>

        <p className="text-xs color-muted sp-runtime-desc">
          <strong>ALL</strong> usa el universo definido aquí (o el de <code>application.yml</code> → <code>universe-tickers</code>, o todo el CSV si están vacíos).
          <strong> HOT</strong> es el orden de prioridad en scans y backtest. Los fundamentales alimentan HYBRID + NewsFilter.
          {hasRuntimeFile ? (
            <span className="color-success"> · Archivo runtime activo.</span>
          ) : (
            <span> · Aún no hay <code>ticker-runtime.json</code>; el primer guardado lo crea.</span>
          )}
        </p>

        {tcError && (
          <div className="badge badge-error mb-12 sp-alert-block" role="alert">{tcError}</div>
        )}

        {tcLoading ? (
          <p className="text-sm color-muted">Cargando…</p>
        ) : (
          <>
            <label className="flex-col gap-6 mb-12">
              <span className="text-xs font-bold color-muted text-uppercase">Universo (comma-separated)</span>
              <textarea
                className="ticker-input sp-textarea"
                rows={3}
                value={universeStr}
                onChange={(e) => setUniverseStr(e.target.value.toUpperCase())}
                placeholder="Vacío en el primer load copia el servidor; editá la lista y guardá."
              />
            </label>
            <label className="flex-col gap-6 mb-12">
              <span className="text-xs font-bold color-muted text-uppercase">HOT — orden de procesamiento primero</span>
              <textarea
                className="ticker-input sp-textarea"
                rows={2}
                value={hotStr}
                onChange={(e) => setHotStr(e.target.value.toUpperCase())}
                placeholder="NVDA, AAPL, MSFT, …"
              />
            </label>

            <div className="flex-align-center gap-8 flex-wrap mb-12">
              <input
                type="text"
                className="ticker-input sp-add-ticker-input"
                placeholder="Nuevo ticker"
                value={newTickerInput}
                onChange={(e) => setNewTickerInput(e.target.value.toUpperCase())}
                onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAddTicker())}
              />
              <button type="button" className="btn btn-secondary" onClick={handleAddTicker}>
                <Plus size={16} /> Añadir al universo
              </button>
            </div>

            <div className="text-xs color-muted mb-8">Fundamentales por símbolo ({universeList.length} en universo)</div>
            <div className="sp-fund-table-wrap">
              <table className="sp-fund-table">
                <thead>
                  <tr className="sp-fund-thead-row">
                    <th className="sp-fund-th">Ticker</th>
                    <th className="sp-fund-th">Empresa</th>
                    <th className="sp-fund-th">Sector</th>
                    <th className="sp-fund-th">Cap B</th>
                    <th className="sp-fund-th">ROIC %</th>
                    <th className="sp-fund-th" />
                  </tr>
                </thead>
                <tbody>
                  {universeList.map((sym) => {
                    const f = fundamentals[sym] || {}
                    return (
                      <tr key={sym} className="sp-fund-row">
                        <td className="sp-fund-td sp-fund-td-bold">{sym}</td>
                        <td className="sp-fund-td">{f.companyName ?? '—'}</td>
                        <td className="sp-fund-td">{f.sector ?? '—'}</td>
                        <td className="sp-fund-td">{f.marketCapBillion != null ? f.marketCapBillion : '—'}</td>
                        <td className="sp-fund-td">{f.roic != null ? f.roic : '—'}</td>
                        <td className="sp-fund-td-actions">
                          <button
                            type="button"
                            className="btn btn-secondary sp-fund-edit-btn"
                            onClick={() => setEditingSymbol(sym)}
                          >
                            Editar
                          </button>
                          {' '}
                          <button
                            type="button"
                            className="btn color-error sp-fund-del-btn"
                            onClick={() => handleRemoveFromUniverse(sym)}
                          >
                            <Trash2 size={14} />
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>

            {editingSymbol && (
              <div className="card sp-fund-edit-card">
                <FundamentalTickerForm
                  symbol={editingSymbol}
                  initial={fundamentals[editingSymbol]}
                  onSave={(payload) => {
                    setFundamentals((prev) => ({ ...prev, [editingSymbol]: payload }))
                    setEditingSymbol(null)
                  }}
                  onCancel={() => setEditingSymbol(null)}
                />
              </div>
            )}
          </>
        )}
      </div>

      <div className="card">
        <div className="flex-between mb-20">
          <h3 className="m-0 flex-align-center gap-8">
            <List size={20} className="color-info" />
            Quick lists (local UI)
          </h3>
          <button className="btn btn-primary" onClick={handleSave}>
            <Save size={16} /> Save Changes
          </button>
        </div>

        {saveStatus && (
          <div className="badge badge-success mb-12 sp-alert-block sp-save-status">{saveStatus}</div>
        )}

        <div className="flex-col gap-15">
          {groups.map((group) => (
            <div key={group.id} className="bg-card border-main rounded-md p-12-20 flex-between">
              <div className="flex-col gap-4 sp-group-info">
                <span className="font-bold color-text">{group.label}</span>
                <span className="text-xs color-muted sp-group-tickers">{group.tickers}</span>
              </div>
              <button
                className="btn color-error sp-group-del-btn"
                onClick={() => setGroups(prev => prev.filter(g => g.id !== group.id))}
              >
                <Trash2 size={18} />
              </button>
            </div>
          ))}
        </div>

        <div className="divider-v sp-divider" />

        <h4 className="mb-12 color-muted text-uppercase text-xs font-bold">Add New Group</h4>
        <div className="grid gap-12 sp-add-group-grid">
          <input
            type="text"
            placeholder="Group Name"
            className="ticker-input"
            value={newGroup.label}
            onChange={e => setNewGroup({ ...newGroup, label: e.target.value })}
          />
          <input
            type="text"
            placeholder="Tickers (comma separated)"
            className="ticker-input"
            value={newGroup.tickers}
            onChange={e => setNewGroup({ ...newGroup, tickers: e.target.value.toUpperCase() })}
          />
          <button className="btn btn-primary" onClick={handleSaveGroup} disabled={!newGroup.label || !newGroup.tickers}>
            <Plus size={18} />
          </button>
        </div>
      </div>

      <div className="card">
        <h3>Platform Preferences</h3>
        <p className="color-muted text-sm">Notificaciones y más opciones próximamente.</p>
      </div>
    </div>
  )
}
