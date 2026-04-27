/**
 * SettingsPage — manages ticker universe/HOT configuration (synced to the server)
 * and quick-list groups (persisted in localStorage).
 *
 * Server config is loaded on mount and written via PUT /api/ticker-config.
 * Quick groups are stored in LS under 'ticker_groups' and synced automatically via useEffect.
 *
 * Note: UI labels intentionally mix Spanish and English — consistent with the rest of the app.
 */
import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { Trash2, List, X, Pencil, Server, Settings, Copy, Flame, RefreshCw } from 'lucide-react'
import { tickerConfigApi, analyticsApi } from '../api'
import FundamentalTickerForm from '../components/FundamentalTickerForm'
import ChipListEditor from '../components/ChipListEditor'
import HotTickerCountEditor from '../components/HotTickerCountEditor'
import { useWatchlists } from '../hooks/useWatchlists'

function parseList(str) {
  if (!str || !String(str).trim()) return []
  return String(str)
    .split(/[\s,]+/)
    .map((s) => s.trim().toUpperCase())
    .filter(Boolean)
}

/** Non-interactive preview chip shown in collapsed watchlist row */
function PreviewChip({ label }) {
  return <span className="sp-wl-chip-preview">{label}</span>
}

/** Collapsed row — shows first 3 tickers + badge + edit/duplicate/delete buttons */
function WatchlistRowCollapsed({ group, onEdit, onDelete, onDuplicate }) {
  const tickers = parseList(group.tickers)
  const preview = tickers.slice(0, 3)
  const rest    = tickers.length - preview.length

  return (
    <tr className="sp-wl-row">
      <td className="sp-fund-td sp-fund-td-bold" style={{ width: '18%' }}>
        <button
          type="button"
          className="sp-wl-row-name-btn"
          onClick={() => onEdit(group.id)}
          title="Editar tickers"
        >
          ▶ {group.label}
        </button>
      </td>
      <td className="sp-fund-td">
        <div className="flex-align-center flex-wrap gap-4">
          {preview.map((t) => <PreviewChip key={t} label={t} />)}
          {rest > 0 && <span className="sp-wl-badge">+{rest} más</span>}
        </div>
      </td>
      <td className="sp-fund-td-actions" style={{ whiteSpace: 'nowrap' }}>
        <button
          type="button"
          className="btn btn-secondary sp-fund-edit-btn"
          onClick={() => onEdit(group.id)}
          title="Editar"
        >
          <Pencil size={13} />
        </button>
        {' '}
        <button
          type="button"
          className="btn btn-secondary sp-fund-edit-btn"
          onClick={() => onDuplicate(group.id)}
          title="Duplicar"
        >
          <Copy size={13} />
        </button>
        {' '}
        <button
          type="button"
          className="btn color-error sp-fund-del-btn"
          onClick={() => onDelete(group.id)}
          title="Eliminar"
        >
          <Trash2 size={14} />
        </button>
      </td>
    </tr>
  )
}

/** Expanded row — inline ChipListEditor with save/cancel */
function WatchlistRowExpanded({ group, onSave, onCancel }) {
  const [editingTickers, setEditingTickers] = useState(group.tickers)
  const [editingLabel, setEditingLabel] = useState(group.label)

  return (
    <tr className="sp-wl-row sp-wl-row--expanded">
      <td className="sp-fund-td sp-fund-td-bold" style={{ width: '18%', verticalAlign: 'top', paddingTop: 14 }}>
        <input
          className="sp-wl-name-input"
          value={editingLabel}
          onChange={(e) => setEditingLabel(e.target.value)}
          placeholder="Nombre…"
        />
      </td>
      <td className="sp-fund-td">
        <ChipListEditor
          value={editingTickers}
          onChange={setEditingTickers}
          placeholder="Tickers…"
          maxHeight="120px"
        />
      </td>
      <td className="sp-fund-td-actions" style={{ verticalAlign: 'top', paddingTop: 14 }}>
        <div className="sp-wl-actions-col">
          <button
            type="button"
            className="btn btn-primary sp-fund-edit-btn"
            style={{ justifyContent: 'center' }}
            onClick={() => { if (editingLabel.trim()) onSave(editingLabel, editingTickers) }}
          >
            Guardar
          </button>
          <button
            type="button"
            className="btn btn-secondary sp-fund-edit-btn"
            style={{ justifyContent: 'center' }}
            onClick={onCancel}
          >
            Cancelar
          </button>
        </div>
      </td>
    </tr>
  )
}

export default function SettingsPage() {
  const { groups, addGroup, removeGroup, updateGroup, updateGroupFull, duplicateGroup } = useWatchlists()
  const [newGroup, setNewGroup]     = useState({ label: '', tickers: '' })
  const [saveStatus, setSaveStatus] = useState(null)
  const [activeTab, setActiveTab]   = useState('servidor')
  const [expandedId, setExpandedId] = useState(null)
  const [pendingDelete, setPendingDelete] = useState(null) // { id, label, timeoutId }

  const [tcLoading, setTcLoading]           = useState(true)
  const [isRefreshing, setIsRefreshing]     = useState(false)
  const [tcError, setTcError]               = useState(null)
  const [universeStr, setUniverseStr]       = useState('')
  const [hotStr, setHotStr]                 = useState('')
  const [fundamentals, setFundamentals]     = useState({})
  const [hasRuntimeFile, setHasRuntimeFile] = useState(false)
  const [editingSymbol, setEditingSymbol]   = useState(null)
  const [newSymbolInput, setNewSymbolInput] = useState('')
  const [symbolError, setSymbolError]       = useState('')
  const [isValidating, setIsValidating]     = useState(false)
  const lastLoadedRef = useRef(null)

  const loadTickerConfig = useCallback(async () => {
    setTcLoading(true)
    setTcError(null)
    try {
      const data = await tickerConfigApi.get()
      const hash = JSON.stringify({ u: data.universe || [], h: data.hot || [] })
      if (hash !== lastLoadedRef.current) {
        lastLoadedRef.current = hash
        setUniverseStr((data.universe || []).join(', '))
        setHotStr((data.hot || []).join(', '))
        setHasRuntimeFile(!!data.hasRuntimeFile)
      }
      // Always update fundamentals regardless of hash
      setFundamentals(data.fundamentals || data.entries || {})
    } catch (e) {
      setTcError(e.message || 'No se pudo cargar /api/ticker-config')
    } finally {
      setTcLoading(false)
    }
  }, [])

  const silentRefresh = useCallback(async () => {
    setIsRefreshing(true)
    try {
      const data = await tickerConfigApi.get()
      const hash = JSON.stringify({ u: data.universe || [], h: data.hot || [] })
      if (hash !== lastLoadedRef.current) {
        lastLoadedRef.current = hash
        setUniverseStr((data.universe || []).join(', '))
        setHotStr((data.hot || []).join(', '))
        setHasRuntimeFile(!!data.hasRuntimeFile)
      }
      // Always update fundamentals — they don't cause layout shifts
      setFundamentals(data.fundamentals || data.entries || {})
    } catch {
      // silent — don't show error for background polls
    } finally {
      setIsRefreshing(false)
    }
  }, [])

  useEffect(() => { loadTickerConfig() }, [loadTickerConfig])
  useEffect(() => {
    const onFocus = () => silentRefresh()
    window.addEventListener('focus', onFocus)
    const id = setInterval(silentRefresh, 30_000)
    return () => {
      window.removeEventListener('focus', onFocus)
      clearInterval(id)
    }
  }, [silentRefresh])
  useEffect(() => {
    if (!editingSymbol) return
    const onKey = (e) => { if (e.key === 'Escape') setEditingSymbol(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [editingSymbol])

  const handleToggleHot = async (sym) => {
    const currentHot = parseList(hotStr)
    const newHot = hotSet.has(sym)
      ? currentHot.filter(t => t !== sym)
      : [...currentHot, sym]
    setHotStr(newHot.join(', '))
    lastLoadedRef.current = JSON.stringify({ u: parseList(universeStr), h: newHot })
    try {
      await tickerConfigApi.put({ universe: parseList(universeStr), hot: newHot, fundamentals })
      window.dispatchEvent(new CustomEvent('app-ls-change', { detail: { key: 'ticker-hot' } }))
    } catch (e) {
      console.warn('Toggle HOT failed', e)
    }
  }

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

  const handleAddSymbol = async () => {
    const sym = newSymbolInput.trim().toUpperCase()
    if (!sym) return
    if (!/^[A-Z]{1,5}$/.test(sym)) {
      setSymbolError('Símbolo inválido — solo letras A-Z, máximo 5 caracteres')
      return
    }
    const current = parseList(universeStr)
    if (current.includes(sym)) { setNewSymbolInput(''); return }

    // Validate against TWS
    setIsValidating(true)
    setSymbolError('')
    try {
      const result = await tickerConfigApi.validate(sym)
      if (!result.valid) {
        setSymbolError(result.reason || 'Ticker no válido en TWS')
        return
      }
    } catch (e) {
      setSymbolError('Error al validar ticker')
      return
    } finally {
      setIsValidating(false)
    }

    // Proceed with add
    const newUniverse = [...current, sym].sort()
    setUniverseStr(newUniverse.join(', '))
    setNewSymbolInput('')
    lastLoadedRef.current = JSON.stringify({ u: newUniverse, h: parseList(hotStr) })
    try {
      await tickerConfigApi.put({ universe: newUniverse, hot: parseList(hotStr), fundamentals })
    } catch (e) {
      console.warn('Failed to save after adding ticker', e)
    }
  }

  const handleRemoveFromUniverse = async (sym) => {
    const newUniverse = parseList(universeStr).filter((t) => t !== sym).sort()
    const newHot      = parseList(hotStr).filter((t) => t !== sym)
    const newFunds    = { ...fundamentals }
    delete newFunds[sym]
    setUniverseStr(newUniverse.join(', '))
    setHotStr(newHot.join(', '))
    setFundamentals(newFunds)
    if (editingSymbol === sym) setEditingSymbol(null)
    lastLoadedRef.current = JSON.stringify({ u: newUniverse, h: newHot })
    try {
      await tickerConfigApi.put({
        universe: newUniverse,
        hot: newHot,
        fundamentals: newFunds,
      })
    } catch (e) {
      console.warn('Failed to save after removing ticker', e)
    }
  }

  // No deps — uses functional setFundamentals so it never goes stale
  const fetchAndStoreFundamentals = useCallback(async (sym) => {
    try {
      const info = await analyticsApi.getTickerInfo(sym)
      if (!info?.companyName) return
      setFundamentals((prev) => {
        if (prev[sym]?.companyName && prev[sym].companyName !== sym) return prev
        return {
          ...prev,
          [sym]: {
            companyName:      info.companyName,
            sector:           info.sector           ?? prev[sym]?.sector           ?? null,
            marketCapBillion: info.marketCapBillion  ?? prev[sym]?.marketCapBillion ?? null,
            peRatio:          info.peRatio           ?? prev[sym]?.peRatio          ?? null,
            dividendYield:    info.dividendYield     ?? prev[sym]?.dividendYield    ?? null,
            beta:             info.beta              ?? prev[sym]?.beta              ?? null,
            epsGrowth:        info.epsGrowth         ?? prev[sym]?.epsGrowth        ?? null,
            revenueGrowth:    info.revenueGrowth     ?? prev[sym]?.revenueGrowth    ?? null,
            debtToEquity:     info.debtToEquity      ?? prev[sym]?.debtToEquity     ?? null,
            roic:             info.roic              ?? prev[sym]?.roic              ?? null,
            notes:            prev[sym]?.notes       ?? null,
          },
        }
      })
    } catch { /* sidecar offline — silently skip */ }
  }, [])

  const handleSaveGroup = () => {
    if (!newGroup.label || !newGroup.tickers) return
    addGroup(newGroup.label, newGroup.tickers)
    setNewGroup({ label: '', tickers: '' })
  }

  const handleEditGroup = (id) => {
    setExpandedId((prev) => (prev === id ? null : id))
  }

  const handleSaveEditGroup = (id, newLabel, newTickers) => {
    updateGroupFull(id, newLabel, newTickers)
    setExpandedId(null)
  }

  const handleDuplicateGroup = (id) => duplicateGroup(id)

  const handleDeleteGroup = (id, label) => {
    // If there's already a pending delete, cancel it without deleting
    if (pendingDelete) {
      clearTimeout(pendingDelete.timeoutId)
    }
    const timeoutId = setTimeout(() => {
      removeGroup(id)
      setPendingDelete(null)
    }, 4000)
    setPendingDelete({ id, label, timeoutId })
  }

  const handleUndo = () => {
    clearTimeout(pendingDelete.timeoutId)
    setPendingDelete(null)
  }

  const handleToastDismiss = () => {
    clearTimeout(pendingDelete.timeoutId)
    removeGroup(pendingDelete.id)
    setPendingDelete(null)
  }

  useEffect(() => () => {
    if (pendingDelete?.timeoutId) clearTimeout(pendingDelete.timeoutId)
  }, [pendingDelete])

  const universeList = useMemo(() => parseList(universeStr), [universeStr])
  const hotSet       = useMemo(() => new Set(parseList(hotStr)), [hotStr])

  const TABS = [
    { id: 'servidor',     label: 'Servidor',     icon: <Server size={14} /> },
    { id: 'watchlists',   label: 'Watchlists',   icon: <List size={14} /> },
    { id: 'preferencias', label: 'Preferencias', icon: <Settings size={14} /> },
  ]

  return (
    <div className="flex-col gap-20 sp-container">

      {/* Tab bar */}
      <div className="sp-tabs">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`sp-tab${activeTab === tab.id ? ' sp-tab--active' : ''}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.icon}
            {tab.label}
          </button>
        ))}
      </div>

      {/* ── Tab: Servidor ────────────────────────────────────────────── */}
      {activeTab === 'servidor' && (
        <div className="card">
          {tcError && (
            <div className="badge badge-error mb-12 sp-alert-block" role="alert">{tcError}</div>
          )}

          {saveStatus && (
            <div className="badge badge-success mb-12 sp-alert-block sp-save-status">{saveStatus}</div>
          )}

          {tcLoading ? (
            <p className="text-sm color-muted">Cargando…</p>
          ) : (
            <>
              <div className="flex-between mb-20">
                <h3 className="m-0">
                  Configuración de tickers{' '}
                  <span className="text-xs color-muted font-normal">({universeList.length} en universo)</span>
                  {isRefreshing && <RefreshCw size={12} className="sp-spin color-muted" style={{ marginLeft: 6 }} />}
                </h3>
                <div className="flex-col gap-4" style={{ alignItems: 'flex-end' }}>
                  <div className="flex-align-center gap-8">
                    <input
                      type="text"
                      className="ticker-input"
                      placeholder="Símbolo…"
                      value={newSymbolInput}
                      onChange={e => { setNewSymbolInput(e.target.value.toUpperCase()); setSymbolError('') }}
                      onKeyDown={e => { if (e.key === 'Enter') handleAddSymbol() }}
                      disabled={isValidating}
                    />
                    <button
                      className="btn btn-primary"
                      onClick={handleAddSymbol}
                      disabled={!newSymbolInput.trim() || isValidating}
                    >
                      {isValidating ? 'Validando…' : 'Agregar'}
                    </button>
                  </div>
                  {symbolError && <span className="text-xs color-error">{symbolError}</span>}
                </div>
              </div>
              <div className="sp-fund-table-wrap">
                <table className="sp-fund-table">
                  <thead>
                    <tr className="sp-fund-thead-row">
                      <th className="sp-fund-th">Ticker</th>
                      <th className="sp-fund-th">Empresa</th>
                      <th className="sp-fund-th">Sector</th>
                      <th className="sp-fund-th">Cap B</th>
                      <th className="sp-fund-th">ROIC %</th>
                      <th className="sp-fund-th" style={{ width: 32 }}>🔥</th>
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
                          <td className="sp-fund-td" style={{ textAlign: 'center' }}>
                            <button
                              type="button"
                              className="sp-fund-hot-btn"
                              onClick={() => handleToggleHot(sym)}
                              title={hotSet.has(sym) ? 'Quitar de HOT' : 'Agregar a HOT'}
                            >
                              <Flame size={14} color={hotSet.has(sym) ? '#f85149' : '#6e7681'} />
                            </button>
                          </td>
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
                <div
                  role="dialog"
                  aria-modal="true"
                  aria-labelledby="sp-fund-modal-title"
                  className="bt-modal-backdrop"
                  onClick={(e) => { if (e.target === e.currentTarget) setEditingSymbol(null) }}
                >
                  <div className="card bt-modal-card" onClick={(e) => e.stopPropagation()}>
                    <button
                      type="button"
                      aria-label="Cerrar"
                      className="btn btn-secondary bt-modal-close"
                      onClick={() => setEditingSymbol(null)}
                    >
                      <X size={18} />
                    </button>
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
                </div>
              )}

              <div className="divider-v sp-divider" />
              <HotTickerCountEditor />
            </>
          )}
        </div>
      )}

      {/* ── Tab: Watchlists ──────────────────────────────────────────── */}
      {activeTab === 'watchlists' && (
        <div className="card">
          <div className="flex-between mb-20">
            <h3 className="m-0 flex-align-center gap-8">
              <List size={20} className="color-info" />
              Watchlists
            </h3>
          </div>

          <h4 className="mb-12 color-muted text-uppercase text-xs font-bold">Nueva Watchlist</h4>
          <div className="grid gap-12 sp-add-group-grid">
            <input
              type="text"
              placeholder="Group Name"
              className="ticker-input"
              value={newGroup.label}
              onChange={e => setNewGroup({ ...newGroup, label: e.target.value })}
            />
            <ChipListEditor
              value={newGroup.tickers}
              onChange={v => setNewGroup({ ...newGroup, tickers: v })}
              suggestions={universeList}
              placeholder="Tickers…"
            />
            <button className="btn btn-primary" onClick={handleSaveGroup} disabled={!newGroup.label || !newGroup.tickers}>
              Crear watchlist
            </button>
          </div>

          <div className="divider-v sp-divider" />

          <div className="sp-wl-table-wrap">
            <table className="sp-wl-table">
              <thead>
                <tr className="sp-fund-thead-row">
                  <th className="sp-fund-th">Nombre</th>
                  <th className="sp-fund-th">Tickers</th>
                  <th className="sp-fund-th" style={{ width: 120 }} />
                </tr>
              </thead>
              <tbody>
                {groups.filter(g => g.id !== pendingDelete?.id).map((group) =>
                  expandedId === group.id ? (
                    <WatchlistRowExpanded
                      key={group.id}
                      group={group}
                      onSave={(newLabel, newTickers) => handleSaveEditGroup(group.id, newLabel, newTickers)}
                      onCancel={() => setExpandedId(null)}
                    />
                  ) : (
                    <WatchlistRowCollapsed
                      key={group.id}
                      group={group}
                      onEdit={handleEditGroup}
                      onDelete={(id) => handleDeleteGroup(id, group.label)}
                      onDuplicate={handleDuplicateGroup}
                    />
                  )
                )}
              </tbody>
            </table>
          </div>

          {pendingDelete && (
            <div className="sp-undo-toast">
              <span>"{pendingDelete.label}" eliminada</span>
              <button onClick={handleUndo}>Deshacer</button>
              <button onClick={handleToastDismiss}>✕</button>
            </div>
          )}
        </div>
      )}

      {/* ── Tab: Preferencias ────────────────────────────────────────── */}
      {activeTab === 'preferencias' && (
        <div className="card">
          <h3>Platform Preferences</h3>
          <p className="color-muted text-sm">Notificaciones y más opciones próximamente.</p>
        </div>
      )}

    </div>
  )
}
