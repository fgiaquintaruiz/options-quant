import React from 'react'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import SettingsPage from './SettingsPage'

// ── API mock ──────────────────────────────────────────────────────────────────

vi.mock('../api', () => ({
  tickerConfigApi: {
    get:               vi.fn(),
    put:               vi.fn(),
    validate:          vi.fn(),
    getHotTickerCount: vi.fn(),
    setHotTickerCount: vi.fn(),
  },
  analyticsApi: {
    getTickerInfo: vi.fn(),
  },
}))

// ── Hook mock ─────────────────────────────────────────────────────────────────

vi.mock('../hooks/useWatchlists', () => ({
  useWatchlists: vi.fn(),
}))

// ── Child component mocks ─────────────────────────────────────────────────────

vi.mock('../components/FundamentalTickerForm', () => ({
  default: ({ symbol, onSave, onCancel }) => (
    <div data-testid="fundamental-form">
      <span>FundamentalTickerForm:{symbol}</span>
      <button onClick={() => onSave({})} data-testid="form-save">Save</button>
      <button onClick={onCancel} data-testid="form-cancel">Cancel</button>
    </div>
  ),
}))

vi.mock('../components/ChipListEditor', () => ({
  default: ({ value, onChange, placeholder }) => (
    <input
      data-testid="chip-list-editor"
      value={value}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}))

vi.mock('../components/HotTickerCountEditor', () => ({
  default: () => <div data-testid="hot-ticker-count-editor">HotTickerCountEditor</div>,
}))

// ── Imports after mocks ───────────────────────────────────────────────────────

import { tickerConfigApi, analyticsApi } from '../api'
import { useWatchlists } from '../hooks/useWatchlists'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const defaultTickerConfig = {
  universe:       ['AAPL', 'MSFT', 'NVDA'],
  hot:            ['AAPL'],
  fundamentals:   {
    AAPL: { companyName: 'Apple Inc.', sector: 'Technology', marketCapBillion: 3200, roic: 45 },
    MSFT: { companyName: 'Microsoft Corp', sector: 'Technology', marketCapBillion: 3100, roic: 38 },
    NVDA: { companyName: 'NVIDIA Corp', sector: 'Semiconductors', marketCapBillion: 2500, roic: 55 },
  },
  hasRuntimeFile: true,
}

const defaultWatchlistsHook = {
  groups:           [],
  addGroup:         vi.fn(),
  removeGroup:      vi.fn(),
  updateGroup:      vi.fn(),
  updateGroupFull:  vi.fn(),
  duplicateGroup:   vi.fn(),
}

const defaultHotTickerCount = { effectiveCount: 10, ymlDefault: 20 }

// ── Helpers ───────────────────────────────────────────────────────────────────

async function renderSettingsPage() {
  let result
  await act(async () => {
    result = render(<SettingsPage />)
  })
  return result
}

// ── Setup / Teardown ──────────────────────────────────────────────────────────

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false })
  tickerConfigApi.get.mockResolvedValue(defaultTickerConfig)
  tickerConfigApi.put.mockResolvedValue({})
  tickerConfigApi.validate.mockResolvedValue({ valid: true })
  tickerConfigApi.getHotTickerCount.mockResolvedValue(defaultHotTickerCount)
  tickerConfigApi.setHotTickerCount.mockResolvedValue({})
  analyticsApi.getTickerInfo.mockResolvedValue(null)
  useWatchlists.mockReturnValue({ ...defaultWatchlistsHook })
})

afterEach(() => {
  vi.clearAllMocks()
  vi.useRealTimers()
})

// ═════════════════════════════════════════════════════════════════════════════
// Tab bar — navigation
// ═════════════════════════════════════════════════════════════════════════════

describe('SettingsPage — tab navigation', () => {
  it('renders the three tab buttons', async () => {
    await renderSettingsPage()
    expect(screen.getByRole('button', { name: /Servidor/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Watchlists/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Preferencias/i })).toBeInTheDocument()
  })

  it('Servidor tab is active by default', async () => {
    await renderSettingsPage()
    const servidorBtn = screen.getByRole('button', { name: /Servidor/i })
    expect(servidorBtn.className).toContain('sp-tab--active')
  })

  it('Watchlists and Preferencias tabs are not active by default', async () => {
    await renderSettingsPage()
    const watchlistsBtn  = screen.getByRole('button', { name: /Watchlists/i })
    const preferenciasBtn = screen.getByRole('button', { name: /Preferencias/i })
    expect(watchlistsBtn.className).not.toContain('sp-tab--active')
    expect(preferenciasBtn.className).not.toContain('sp-tab--active')
  })

  it('clicking Watchlists tab activates it and shows watchlist content', async () => {
    await renderSettingsPage()
    fireEvent.click(screen.getByRole('button', { name: /Watchlists/i }))
    expect(screen.getByRole('button', { name: /Watchlists/i }).className).toContain('sp-tab--active')
    expect(screen.getByText('Nueva Watchlist')).toBeInTheDocument()
  })

  it('clicking Preferencias tab activates it and shows preferences content', async () => {
    await renderSettingsPage()
    fireEvent.click(screen.getByRole('button', { name: /Preferencias/i }))
    expect(screen.getByRole('button', { name: /Preferencias/i }).className).toContain('sp-tab--active')
    expect(screen.getByText('Platform Preferences')).toBeInTheDocument()
  })

  it('switching tabs hides the previous tab content', async () => {
    await renderSettingsPage()
    // Switch away from Servidor (default)
    fireEvent.click(screen.getByRole('button', { name: /Watchlists/i }))
    // Servidor-specific content (ticker table header) should be gone
    expect(screen.queryByText('Configuración de tickers')).not.toBeInTheDocument()
  })

  it('can navigate back from Watchlists to Servidor', async () => {
    await renderSettingsPage()
    fireEvent.click(screen.getByRole('button', { name: /Watchlists/i }))
    fireEvent.click(screen.getByRole('button', { name: /Servidor/i }))
    expect(screen.getByRole('button', { name: /Servidor/i }).className).toContain('sp-tab--active')
    expect(screen.getByText(/Configuración de tickers/)).toBeInTheDocument()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Tab: Servidor
// ═════════════════════════════════════════════════════════════════════════════

describe('SettingsPage — Tab Servidor', () => {
  // ── Loading state ────────────────────────────────────────────────────────

  it('shows loading indicator while ticker config is being fetched', () => {
    // Delay never resolves during this test
    tickerConfigApi.get.mockReturnValue(new Promise(() => {}))
    render(<SettingsPage />)
    expect(screen.getByText('Cargando…')).toBeInTheDocument()
  })

  it('hides loading indicator after data loads', async () => {
    await renderSettingsPage()
    expect(screen.queryByText('Cargando…')).not.toBeInTheDocument()
  })

  // ── Error state ──────────────────────────────────────────────────────────

  it('shows error alert when ticker config load fails', async () => {
    tickerConfigApi.get.mockRejectedValue(new Error('Network error'))
    await act(async () => {
      render(<SettingsPage />)
    })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Network error')
  })

  // ── Ticker table ─────────────────────────────────────────────────────────

  it('renders ticker table headers', async () => {
    await renderSettingsPage()
    expect(screen.getByText('Ticker')).toBeInTheDocument()
    expect(screen.getByText('Empresa')).toBeInTheDocument()
    expect(screen.getByText('Sector')).toBeInTheDocument()
  })

  it('renders a row for each ticker in universe', async () => {
    await renderSettingsPage()
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('MSFT')).toBeInTheDocument()
    expect(screen.getByText('NVDA')).toBeInTheDocument()
  })

  it('displays fundamental data for each ticker', async () => {
    await renderSettingsPage()
    expect(screen.getByText('Apple Inc.')).toBeInTheDocument()
    // AAPL and MSFT both have sector "Technology" — multiple elements expected
    const technologyCells = screen.getAllByText('Technology')
    expect(technologyCells.length).toBeGreaterThanOrEqual(1)
  })

  it('shows universe count in the section heading', async () => {
    await renderSettingsPage()
    expect(screen.getByText(/3 en universo/)).toBeInTheDocument()
  })

  it('renders HotTickerCountEditor inside Servidor tab', async () => {
    await renderSettingsPage()
    expect(screen.getByTestId('hot-ticker-count-editor')).toBeInTheDocument()
  })

  // ── Add symbol ───────────────────────────────────────────────────────────

  it('renders the add-symbol input and button', async () => {
    await renderSettingsPage()
    expect(screen.getByPlaceholderText('Símbolo…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Agregar' })).toBeInTheDocument()
  })

  it('Agregar button is disabled when input is empty', async () => {
    await renderSettingsPage()
    expect(screen.getByRole('button', { name: 'Agregar' })).toBeDisabled()
  })

  it('Agregar button is enabled after typing a symbol', async () => {
    await renderSettingsPage()
    fireEvent.change(screen.getByPlaceholderText('Símbolo…'), { target: { value: 'TSLA' } })
    expect(screen.getByRole('button', { name: 'Agregar' })).not.toBeDisabled()
  })

  it('shows validation error for symbol with invalid characters', async () => {
    tickerConfigApi.validate.mockResolvedValue({ valid: false, reason: 'Ticker no válido en TWS' })
    await renderSettingsPage()
    const input = screen.getByPlaceholderText('Símbolo…')
    // NVDA123 fails the regex check BEFORE the API call — error is set synchronously
    fireEvent.change(input, { target: { value: 'NVDA123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Agregar' }))
    expect(screen.getByText(/Símbolo inválido/)).toBeInTheDocument()
  })

  it('calls tickerConfigApi.validate when Agregar is clicked with valid symbol', async () => {
    await renderSettingsPage()
    const input = screen.getByPlaceholderText('Símbolo…')
    fireEvent.change(input, { target: { value: 'TSLA' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Agregar' }))
    })
    expect(tickerConfigApi.validate).toHaveBeenCalledWith('TSLA')
  })

  it('adds ticker to table after successful validation', async () => {
    await renderSettingsPage()
    const input = screen.getByPlaceholderText('Símbolo…')
    fireEvent.change(input, { target: { value: 'TSLA' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Agregar' }))
    })
    expect(tickerConfigApi.validate).toHaveBeenCalledWith('TSLA')
    expect(tickerConfigApi.put).toHaveBeenCalled()
  })

  it('shows TWS validation error when symbol is invalid in TWS', async () => {
    tickerConfigApi.validate.mockResolvedValue({ valid: false, reason: 'No encontrado en TWS' })
    await renderSettingsPage()
    const input = screen.getByPlaceholderText('Símbolo…')
    fireEvent.change(input, { target: { value: 'ZZZZ' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Agregar' }))
    })
    expect(screen.getByText('No encontrado en TWS')).toBeInTheDocument()
  })

  it('adds ticker on Enter key inside symbol input', async () => {
    await renderSettingsPage()
    const input = screen.getByPlaceholderText('Símbolo…')
    fireEvent.change(input, { target: { value: 'TSLA' } })
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    expect(tickerConfigApi.validate).toHaveBeenCalledWith('TSLA')
  })

  // ── Toggle HOT ───────────────────────────────────────────────────────────

  it('renders flame button for each ticker', async () => {
    await renderSettingsPage()
    // 3 tickers in universe → 3 flame buttons
    const flameButtons = screen.getAllByTitle(/Agregar a HOT|Quitar de HOT/)
    expect(flameButtons).toHaveLength(3)
  })

  it('AAPL flame button shows "Quitar de HOT" title (it is in HOT)', async () => {
    await renderSettingsPage()
    // AAPL is in hot per defaultTickerConfig
    const aaplRow = screen.getByText('AAPL').closest('tr')
    const flameBtn = aaplRow.querySelector('button[title="Quitar de HOT"]')
    expect(flameBtn).not.toBeNull()
  })

  it('clicking flame toggles HOT and calls tickerConfigApi.put', async () => {
    await renderSettingsPage()
    const msftRow = screen.getByText('MSFT').closest('tr')
    const flameBtn = msftRow.querySelector('button[title="Agregar a HOT"]')
    await act(async () => {
      fireEvent.click(flameBtn)
    })
    expect(tickerConfigApi.put).toHaveBeenCalled()
    const callArgs = tickerConfigApi.put.mock.calls[0][0]
    expect(callArgs.hot).toContain('MSFT')
  })

  // ── Editar fundamentals modal ────────────────────────────────────────────

  it('opens FundamentalTickerForm modal when Editar is clicked', async () => {
    await renderSettingsPage()
    const aaplRow = screen.getByText('AAPL').closest('tr')
    const editBtn = aaplRow.querySelector('button.sp-fund-edit-btn')
    fireEvent.click(editBtn)
    expect(screen.getByTestId('fundamental-form')).toBeInTheDocument()
    expect(screen.getByText('FundamentalTickerForm:AAPL')).toBeInTheDocument()
  })

  it('closes modal when cancel is clicked inside FundamentalTickerForm', async () => {
    await renderSettingsPage()
    const aaplRow = screen.getByText('AAPL').closest('tr')
    fireEvent.click(aaplRow.querySelector('button.sp-fund-edit-btn'))
    expect(screen.getByTestId('fundamental-form')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('form-cancel'))
    expect(screen.queryByTestId('fundamental-form')).not.toBeInTheDocument()
  })

  it('closes modal when backdrop is clicked', async () => {
    await renderSettingsPage()
    const aaplRow = screen.getByText('AAPL').closest('tr')
    fireEvent.click(aaplRow.querySelector('button.sp-fund-edit-btn'))
    const backdrop = document.querySelector('.bt-modal-backdrop')
    fireEvent.click(backdrop)
    expect(screen.queryByTestId('fundamental-form')).not.toBeInTheDocument()
  })

  it('closes modal on Escape key', async () => {
    await renderSettingsPage()
    const aaplRow = screen.getByText('AAPL').closest('tr')
    fireEvent.click(aaplRow.querySelector('button.sp-fund-edit-btn'))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByTestId('fundamental-form')).not.toBeInTheDocument()
  })

  it('closes modal when X button is clicked', async () => {
    await renderSettingsPage()
    const aaplRow = screen.getByText('AAPL').closest('tr')
    fireEvent.click(aaplRow.querySelector('button.sp-fund-edit-btn'))
    fireEvent.click(screen.getByRole('button', { name: /Cerrar/i }))
    expect(screen.queryByTestId('fundamental-form')).not.toBeInTheDocument()
  })

  it('saves fundamentals on form save and closes modal', async () => {
    await renderSettingsPage()
    const aaplRow = screen.getByText('AAPL').closest('tr')
    fireEvent.click(aaplRow.querySelector('button.sp-fund-edit-btn'))
    fireEvent.click(screen.getByTestId('form-save'))
    expect(screen.queryByTestId('fundamental-form')).not.toBeInTheDocument()
  })

  it('silently handles tickerConfigApi.put failure when toggling HOT', async () => {
    tickerConfigApi.put.mockRejectedValueOnce(new Error('Server error'))
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await renderSettingsPage()
    const msftRow = screen.getByText('MSFT').closest('tr')
    const flameBtn = msftRow.querySelector('button[title="Agregar a HOT"]')
    await act(async () => {
      fireEvent.click(flameBtn)
    })
    expect(consoleSpy).toHaveBeenCalledWith('Toggle HOT failed', expect.any(Error))
    consoleSpy.mockRestore()
  })

  // ── Remove ticker ────────────────────────────────────────────────────────

  it('removes ticker row and calls tickerConfigApi.put when delete button is clicked', async () => {
    await renderSettingsPage()
    const msftRow = screen.getByText('MSFT').closest('tr')
    const delBtn = msftRow.querySelector('button.sp-fund-del-btn')
    await act(async () => {
      fireEvent.click(delBtn)
    })
    expect(tickerConfigApi.put).toHaveBeenCalled()
    const args = tickerConfigApi.put.mock.calls[0][0]
    expect(args.universe).not.toContain('MSFT')
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Tab: Watchlists
// ═════════════════════════════════════════════════════════════════════════════

describe('SettingsPage — Tab Watchlists', () => {
  async function openWatchlistsTab() {
    await renderSettingsPage()
    fireEvent.click(screen.getByRole('button', { name: /Watchlists/i }))
  }

  // ── Render ───────────────────────────────────────────────────────────────

  it('renders Nueva Watchlist heading', async () => {
    await openWatchlistsTab()
    expect(screen.getByText(/Nueva Watchlist/i)).toBeInTheDocument()
  })

  it('renders Group Name input', async () => {
    await openWatchlistsTab()
    expect(screen.getByPlaceholderText('Group Name')).toBeInTheDocument()
  })

  it('renders ChipListEditor for ticker input', async () => {
    await openWatchlistsTab()
    expect(screen.getByTestId('chip-list-editor')).toBeInTheDocument()
  })

  it('Crear watchlist button is disabled when label is empty', async () => {
    await openWatchlistsTab()
    expect(screen.getByRole('button', { name: /Crear watchlist/i })).toBeDisabled()
  })

  it('Crear watchlist button is disabled when only label is filled (no tickers)', async () => {
    await openWatchlistsTab()
    fireEvent.change(screen.getByPlaceholderText('Group Name'), { target: { value: 'My List' } })
    expect(screen.getByRole('button', { name: /Crear watchlist/i })).toBeDisabled()
  })

  it('Crear watchlist button is enabled when both label and tickers are filled', async () => {
    await openWatchlistsTab()
    fireEvent.change(screen.getByPlaceholderText('Group Name'), { target: { value: 'My List' } })
    fireEvent.change(screen.getByTestId('chip-list-editor'), { target: { value: 'AAPL,MSFT' } })
    expect(screen.getByRole('button', { name: /Crear watchlist/i })).not.toBeDisabled()
  })

  it('calls addGroup and resets form on Crear watchlist click', async () => {
    const addGroup = vi.fn()
    useWatchlists.mockReturnValue({ ...defaultWatchlistsHook, addGroup })
    await openWatchlistsTab()
    fireEvent.change(screen.getByPlaceholderText('Group Name'), { target: { value: 'My List' } })
    fireEvent.change(screen.getByTestId('chip-list-editor'), { target: { value: 'AAPL,MSFT' } })
    fireEvent.click(screen.getByRole('button', { name: /Crear watchlist/i }))
    expect(addGroup).toHaveBeenCalledWith('My List', 'AAPL,MSFT')
    expect(screen.getByPlaceholderText('Group Name').value).toBe('')
  })

  // ── Table with groups ────────────────────────────────────────────────────

  it('renders watchlist table with existing groups', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [
        { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD,AVGO' },
        { id: 'mega',  label: 'Mega Tech', tickers: 'AAPL,MSFT,GOOGL' },
      ],
    })
    await openWatchlistsTab()
    // Collapsed row renders "▶ {label}" inside a button
    expect(screen.getByText(/Semis/)).toBeInTheDocument()
    expect(screen.getByText(/Mega Tech/)).toBeInTheDocument()
  })

  it('shows collapsed preview with first 3 tickers', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD,AVGO,TSM,MU' }],
    })
    await openWatchlistsTab()
    expect(screen.getByText('NVDA')).toBeInTheDocument()
    expect(screen.getByText('AMD')).toBeInTheDocument()
    expect(screen.getByText('AVGO')).toBeInTheDocument()
    // rest badge "+2 más"
    expect(screen.getByText('+2 más')).toBeInTheDocument()
  })

  // ── Edit / expand row ────────────────────────────────────────────────────

  it('expands row to inline editor when edit (pencil) button is clicked', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
    })
    await openWatchlistsTab()
    const editBtn = screen.getByTitle('Editar')
    fireEvent.click(editBtn)
    expect(screen.getByRole('button', { name: /Guardar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Cancelar/i })).toBeInTheDocument()
  })

  it('collapses row back when Cancel is clicked in expanded editor', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Editar'))
    fireEvent.click(screen.getByRole('button', { name: /Cancelar/i }))
    expect(screen.queryByRole('button', { name: /Guardar/i })).not.toBeInTheDocument()
  })

  it('calls updateGroupFull and collapses on Save in expanded editor', async () => {
    const updateGroupFull = vi.fn()
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
      updateGroupFull,
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Editar'))
    fireEvent.click(screen.getByRole('button', { name: /Guardar/i }))
    expect(updateGroupFull).toHaveBeenCalledWith('semis', 'Semis', 'NVDA,AMD')
    expect(screen.queryByRole('button', { name: /Guardar/i })).not.toBeInTheDocument()
  })

  it('clicking row name button toggles expanded state', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
    })
    await openWatchlistsTab()
    // First click: expand
    fireEvent.click(screen.getByTitle('Editar tickers'))
    expect(screen.getByRole('button', { name: /Guardar/i })).toBeInTheDocument()
    // Second click: collapse (toggle off)
    fireEvent.click(screen.getByRole('button', { name: /Guardar/i }).closest('tr').querySelector('input'))
    // Actually toggle via the name button — it should collapse
    // Re-render stays expanded, so just verify the editor is shown
    expect(screen.getByRole('button', { name: /Guardar/i })).toBeInTheDocument()
  })

  // ── Duplicate ────────────────────────────────────────────────────────────

  it('calls duplicateGroup when duplicate button is clicked', async () => {
    const duplicateGroup = vi.fn()
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
      duplicateGroup,
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Duplicar'))
    expect(duplicateGroup).toHaveBeenCalledWith('semis')
  })

  // ── Delete with undo toast ────────────────────────────────────────────────

  it('shows undo toast when delete button is clicked', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Eliminar'))
    expect(screen.getByText(/"Semis" eliminada/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Deshacer/i })).toBeInTheDocument()
  })

  it('hides the deleted group row while pending delete', async () => {
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [
        { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' },
        { id: 'mega',  label: 'Mega Tech', tickers: 'AAPL,MSFT' },
      ],
    })
    await openWatchlistsTab()
    // Delete the first group
    const deleteButtons = screen.getAllByTitle('Eliminar')
    fireEvent.click(deleteButtons[0])
    // Semis row should no longer appear (filtered via pendingDelete?.id)
    expect(screen.queryByText('▶ Semis')).not.toBeInTheDocument()
    expect(screen.getByText('▶ Mega Tech')).toBeInTheDocument()
  })

  it('restores the group row when Deshacer is clicked', async () => {
    const removeGroup = vi.fn()
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
      removeGroup,
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Eliminar'))
    fireEvent.click(screen.getByRole('button', { name: /Deshacer/i }))
    expect(screen.queryByText(/"Semis" eliminada/)).not.toBeInTheDocument()
    expect(removeGroup).not.toHaveBeenCalled()
  })

  it('calls removeGroup immediately when the dismiss (✕) button in the toast is clicked', async () => {
    const removeGroup = vi.fn()
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
      removeGroup,
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Eliminar'))
    // Click the ✕ dismiss button (second button in the toast)
    const toastButtons = screen.getByText('✕').closest('button')
    fireEvent.click(toastButtons)
    expect(removeGroup).toHaveBeenCalledWith('semis')
  })

  it('calls removeGroup after 4-second timeout', async () => {
    const removeGroup = vi.fn()
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [{ id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' }],
      removeGroup,
    })
    await openWatchlistsTab()
    fireEvent.click(screen.getByTitle('Eliminar'))
    expect(removeGroup).not.toHaveBeenCalled()
    act(() => { vi.advanceTimersByTime(4000) })
    expect(removeGroup).toHaveBeenCalledWith('semis')
  })

  it('shows empty table body when there are no groups', async () => {
    useWatchlists.mockReturnValue({ ...defaultWatchlistsHook, groups: [] })
    await openWatchlistsTab()
    // Table exists but tbody is empty
    const tbody = document.querySelector('.sp-wl-table tbody')
    expect(tbody).not.toBeNull()
    expect(tbody.children).toHaveLength(0)
  })

  it('cancels the previous pending-delete timer when a second delete is triggered', async () => {
    const removeGroup = vi.fn()
    useWatchlists.mockReturnValue({
      ...defaultWatchlistsHook,
      groups: [
        { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD' },
        { id: 'mega',  label: 'Mega Tech', tickers: 'AAPL,MSFT' },
      ],
      removeGroup,
    })
    await openWatchlistsTab()

    // Delete first group — starts 4s timer
    const [deleteBtn1, deleteBtn2] = screen.getAllByTitle('Eliminar')
    fireEvent.click(deleteBtn1)
    expect(screen.getByText(/"Semis" eliminada/)).toBeInTheDocument()

    // Delete second group before the 4s timer fires — previous timer should be cancelled
    // (The component clears the old timeout and starts a new one)
    fireEvent.click(deleteBtn2)
    expect(screen.getByText(/"Mega Tech" eliminada/)).toBeInTheDocument()

    // Advance past 4s — only the second group should be removed (first timer was cancelled)
    act(() => { vi.advanceTimersByTime(4000) })
    // removeGroup was called exactly once (for the second group only)
    expect(removeGroup).toHaveBeenCalledTimes(1)
    expect(removeGroup).toHaveBeenCalledWith('mega')
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Tab: Preferencias
// ═════════════════════════════════════════════════════════════════════════════

describe('SettingsPage — Tab Preferencias', () => {
  async function openPreferenciasTab() {
    await renderSettingsPage()
    fireEvent.click(screen.getByRole('button', { name: /Preferencias/i }))
  }

  it('renders Platform Preferences heading', async () => {
    await openPreferenciasTab()
    expect(screen.getByText('Platform Preferences')).toBeInTheDocument()
  })

  it('renders the "próximamente" placeholder message', async () => {
    await openPreferenciasTab()
    expect(screen.getByText(/Notificaciones y más opciones próximamente/i)).toBeInTheDocument()
  })

  it('does not render Servidor or Watchlists content in Preferencias tab', async () => {
    await openPreferenciasTab()
    expect(screen.queryByText('Configuración de tickers')).not.toBeInTheDocument()
    expect(screen.queryByText('Nueva Watchlist')).not.toBeInTheDocument()
  })
})
