import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, within } from '@testing-library/react'
import LiveTradeGrid from './LiveTradeGrid'

// ── Mocks ────────────────────────────────────────────────────────────────────

// TickerTooltip makes API calls — mock it as a passthrough wrapper
vi.mock('./TickerTooltip', async () => {
  const React = await import('react')
  return {
    default: ({ children }) =>
      React.createElement('span', { 'data-testid': 'ticker-tooltip' }, children),
  }
})

// compareScanRows — pure sort utility, keep real impl but mock to avoid dep issues
vi.mock('../utils/scanRowSort', () => ({
  compareScanRows: vi.fn((a, b) => a.ticker.localeCompare(b.ticker)),
}))

// ── Fixture builders ─────────────────────────────────────────────────────────

/**
 * Builds a trade row as LiveTradeGrid receives it (already mapped by useTradeActions).
 * signalStale=false → fresh signal; tradeStatus='SIGNAL' → Open button visible.
 */
function makeSignal(overrides = {}) {
  return {
    ticker: 'AAPL',
    pattern: 'hammer',
    strategy: 'ORB',
    direction: 'CALL',
    ep: 150,
    tp: 155,
    sl: 145,
    signalFound: '28/04 09:30:00',
    entryAt: new Date().toISOString(),
    exitedAt: '-',
    closePrice: null,
    exitReason: 'LIVE SIGNAL',
    tradeStatus: 'SIGNAL',
    orderId: null,
    tpOrderId: null,
    slOrderId: null,
    signalStale: false,
    ...overrides,
  }
}

/**
 * Builds a trade row that is EXECUTED (Open position → shows Close + Cancel).
 */
function makeTrade(overrides = {}) {
  return makeSignal({
    tradeStatus: 'EXECUTED',
    orderId: 'ORDER-001',
    tpOrderId: 'TP-001',
    slOrderId: 'SL-001',
    ...overrides,
  })
}

/**
 * Builds an external position row.
 */
function makeExternal(overrides = {}) {
  return {
    ticker: 'TSLA',
    avg_cost: 200.5,
    snapshot_timestamp: '14:30:00',
    ...overrides,
  }
}

/**
 * Default handler props — all vi.fn() so assertions are easy.
 */
function makeHandlers(overrides = {}) {
  return {
    onCloseTrade: vi.fn(),
    onCancelTrade: vi.fn(),
    onDeleteSignal: vi.fn(),
    onClearStaleBatch: vi.fn(),
    onClearAllStale: vi.fn(),
    onCloseExternal: vi.fn().mockResolvedValue(undefined),
    onScheduleClose1450: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

// ── Setup / teardown ─────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks()
  // Suppress window.confirm noise — external close tests override per-case
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ── Group 1: Renderizado básico ───────────────────────────────────────────────

describe('LiveTradeGrid — Renderizado básico', () => {

  it('renderiza sin crash con props mínimas (trades=[], externalPositions=[])', () => {
    render(<LiveTradeGrid />)
    expect(screen.getByTestId('live-trade-grid-root')).toBeTruthy()
  })

  it('muestra mensaje de estado vacío cuando no hay trades ni posiciones externas', () => {
    render(<LiveTradeGrid trades={[]} externalPositions={[]} />)
    expect(screen.getByText(/No active signals or positions/i)).toBeTruthy()
  })

  it('mensaje vacío menciona "Start a scan" cuando scanning=false', () => {
    render(<LiveTradeGrid scanning={false} />)
    expect(screen.getByText(/Start a scan to find opportunities/i)).toBeTruthy()
  })

  it('mensaje vacío menciona "Scanning market" cuando scanning=true', () => {
    render(<LiveTradeGrid trades={[]} externalPositions={[]} scanning={true} />)
    expect(screen.getByText(/Scanning market/i)).toBeTruthy()
  })

  it('muestra "Waiting for first scan..." cuando scanActivity está vacío', () => {
    render(<LiveTradeGrid scanActivity={[]} />)
    expect(screen.getByText(/Waiting for first scan/i)).toBeTruthy()
  })

  it('muestra el contador de records combinando trades + externalPositions', () => {
    const trades = [makeSignal({ ticker: 'AAPL' })]
    const ext = [makeExternal({ ticker: 'TSLA' })]
    render(<LiveTradeGrid trades={trades} externalPositions={ext} />)
    expect(screen.getByText('2 records')).toBeTruthy()
  })

  it('renderiza correctamente los datos de un trade normal: ticker, strategy, badge', () => {
    const trade = makeSignal({ ticker: 'NVDA', strategy: 'ORB', tradeStatus: 'SIGNAL' })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.getByText('NVDA')).toBeTruthy()
    expect(screen.getByText('ORB')).toBeTruthy()
    // StatusBadge muestra el label del tradeStatus
    expect(screen.getByText('SIGNAL')).toBeTruthy()
  })

  it('muestra badge HOT cuando el ticker está en hotTickers', () => {
    const trade = makeSignal({ ticker: 'AAPL' })
    render(<LiveTradeGrid trades={[trade]} hotTickers={['AAPL']} />)
    expect(screen.getByText('HOT')).toBeTruthy()
  })

  it('muestra precios EP, TP y SL formateados', () => {
    const trade = makeSignal({ ep: 150, tp: 155, sl: 145 })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.getByText('$150.00')).toBeTruthy()
    expect(screen.getByText('$155.00')).toBeTruthy()
    expect(screen.getByText('$145.00')).toBeTruthy()
  })

  it('muestra badge EXITED para trade con exitedAt válido', () => {
    const trade = makeSignal({ exitedAt: '2026-04-28T14:00:00Z', tradeStatus: 'SIGNAL' })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.getByText('EXITED')).toBeTruthy()
  })

  it('muestra tabla de scan activity cuando scanActivity tiene filas', () => {
    const scan = [{ ticker: 'AAPL', status: 'OK', detail: 'done', scanStarted: '-', scanEnded: '-', duration: '1.2s' }]
    render(<LiveTradeGrid scanActivity={scan} scanScores={{}} />)
    // Table headers visible
    expect(screen.getByText('Scan Started')).toBeTruthy()
  })
})

// ── Group 2: Acciones de trade (Open / Close / Cancel) ───────────────────────

describe('LiveTradeGrid — Acciones de trade', () => {

  it('botón "Open" visible para señal normal (no EXECUTED, no stale)', () => {
    const trade = makeSignal({ tradeStatus: 'SIGNAL', signalStale: false })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} {...handlers} />)
    expect(screen.getByText('Open')).toBeTruthy()
  })

  it('click "Open" llama onCloseTrade con (ticker, ep, true)', () => {
    const trade = makeSignal({ ticker: 'AAPL', ep: 150, tradeStatus: 'SIGNAL', signalStale: false })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} {...handlers} />)

    fireEvent.click(screen.getByText('Open'))

    expect(handlers.onCloseTrade).toHaveBeenCalledTimes(1)
    expect(handlers.onCloseTrade).toHaveBeenCalledWith('AAPL', 150, true)
  })

  it('botón "Open" deshabilitado cuando signalStale=true', () => {
    const trade = makeSignal({ tradeStatus: 'SIGNAL', signalStale: true })
    render(<LiveTradeGrid trades={[trade]} />)
    const openBtn = screen.getByText('Open').closest('button')
    expect(openBtn).toBeDisabled()
  })

  it('click "Open" en señal stale NO llama onCloseTrade', () => {
    const trade = makeSignal({ tradeStatus: 'SIGNAL', signalStale: true })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} {...handlers} />)

    fireEvent.click(screen.getByText('Open'))
    expect(handlers.onCloseTrade).not.toHaveBeenCalled()
  })

  it('botón "Open" muestra "⚠ Open" cuando hay conflicto macro', () => {
    const trade = makeSignal({ direction: 'PUT', tradeStatus: 'SIGNAL', signalStale: false })
    render(<LiveTradeGrid trades={[trade]} macroRegime="BULLISH" />)
    expect(screen.getByText('⚠ Open')).toBeTruthy()
  })

  it('botón "Close" visible para trade EXECUTED', () => {
    const trade = makeTrade({ ticker: 'AAPL' })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.getByText('Close')).toBeTruthy()
  })

  it('click "Close" en posición EXECUTED llama onCloseTrade con (ticker, ep, false, tpOrderId, slOrderId)', () => {
    const trade = makeTrade({ ticker: 'AAPL', ep: 150, tpOrderId: 'TP-1', slOrderId: 'SL-1' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} {...handlers} />)

    fireEvent.click(screen.getByText('Close'))

    expect(handlers.onCloseTrade).toHaveBeenCalledTimes(1)
    expect(handlers.onCloseTrade).toHaveBeenCalledWith('AAPL', 150, false, 'TP-1', 'SL-1')
  })

  it('botón "Cancel" visible cuando trade EXECUTED tiene orderId', () => {
    const trade = makeTrade({ orderId: 'ORDER-001' })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.getByText('Cancel')).toBeTruthy()
  })

  it('click "Cancel" llama onCancelTrade con (ticker, orderId)', () => {
    const trade = makeTrade({ ticker: 'AAPL', orderId: 'ORDER-001' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} {...handlers} />)

    fireEvent.click(screen.getByText('Cancel'))

    expect(handlers.onCancelTrade).toHaveBeenCalledTimes(1)
    expect(handlers.onCancelTrade).toHaveBeenCalledWith('AAPL', 'ORDER-001')
  })

  it('botón "Cancel" NO aparece cuando orderId es null', () => {
    const trade = makeTrade({ orderId: null })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.queryByText('Cancel')).toBeNull()
  })

  it('click en botón Trash2 llama onDeleteSignal con el ticker', () => {
    const trade = makeSignal({ ticker: 'AAPL' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} {...handlers} />)

    // Trash2 button — identified by ltg-delete-btn class
    const deleteBtn = document.querySelector('.ltg-delete-btn')
    fireEvent.click(deleteBtn)

    expect(handlers.onDeleteSignal).toHaveBeenCalledWith('AAPL')
  })

  it('muestra spinner cuando pendingActions[ticker]=true y oculta botones de acción', () => {
    const trade = makeTrade({ ticker: 'AAPL' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} pendingActions={{ AAPL: true }} {...handlers} />)

    // Close button should NOT be visible — spinner shown instead
    expect(screen.queryByText('Close')).toBeNull()
    expect(document.querySelector('.spinner-sm')).toBeTruthy()
  })
})

// ── Group 3: Batch delete de señales stale ───────────────────────────────────

describe('LiveTradeGrid — Batch delete de señales stale', () => {

  it('banner stale visible cuando staleSignalCount > 0', () => {
    render(<LiveTradeGrid staleSignalCount={2} />)
    expect(screen.getByTestId('stale-signal-banner')).toBeTruthy()
  })

  it('banner stale NO visible cuando staleSignalCount = 0', () => {
    render(<LiveTradeGrid staleSignalCount={0} />)
    expect(screen.queryByTestId('stale-signal-banner')).toBeNull()
  })

  it('banner muestra el conteo correcto de señales stale', () => {
    render(<LiveTradeGrid staleSignalCount={3} />)
    // The banner has the count as a <strong> tag
    const banner = screen.getByTestId('stale-signal-banner')
    expect(within(banner).getByText('3')).toBeTruthy()
  })

  it('checkbox aparece para señal stale deletable (no EXECUTED, no exited)', () => {
    const trade = makeSignal({
      ticker: 'AAPL',
      signalStale: true,
      tradeStatus: 'SIGNAL',
      exitedAt: '-',
    })
    render(<LiveTradeGrid trades={[trade]} staleSignalCount={1} />)
    const checkbox = screen.getByLabelText('Seleccionar AAPL')
    expect(checkbox).toBeTruthy()
  })

  it('checkbox en señal stale selecciona esa señal (checked=true al hacer click)', () => {
    const trade = makeSignal({
      ticker: 'AAPL',
      signalStale: true,
      tradeStatus: 'SIGNAL',
      exitedAt: '-',
    })
    render(<LiveTradeGrid trades={[trade]} staleSignalCount={1} />)

    const checkbox = screen.getByLabelText('Seleccionar AAPL')
    expect(checkbox.checked).toBe(false)

    fireEvent.click(checkbox)
    expect(checkbox.checked).toBe(true)
  })

  it('"Seleccionar todas" marca todas las señales stale como checked', () => {
    const tradeA = makeSignal({ ticker: 'AAPL', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    const tradeB = makeSignal({ ticker: 'TSLA', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    render(<LiveTradeGrid trades={[tradeA, tradeB]} staleSignalCount={2} />)

    fireEvent.click(screen.getByText('Seleccionar todas'))

    expect(screen.getByLabelText('Seleccionar AAPL').checked).toBe(true)
    expect(screen.getByLabelText('Seleccionar TSLA').checked).toBe(true)
  })

  it('"Limpiar selección" desmarca todos los checkboxes', () => {
    const tradeA = makeSignal({ ticker: 'AAPL', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    render(<LiveTradeGrid trades={[tradeA]} staleSignalCount={1} />)

    // Select first, then clear
    fireEvent.click(screen.getByText('Seleccionar todas'))
    expect(screen.getByLabelText('Seleccionar AAPL').checked).toBe(true)

    fireEvent.click(screen.getByText('Limpiar selección'))
    expect(screen.getByLabelText('Seleccionar AAPL').checked).toBe(false)
  })

  it('"Borrar seleccionadas" llama onClearStaleBatch con los tickers seleccionados', () => {
    const tradeA = makeSignal({ ticker: 'AAPL', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    const tradeB = makeSignal({ ticker: 'TSLA', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[tradeA, tradeB]} staleSignalCount={2} {...handlers} />)

    // Select AAPL only
    fireEvent.click(screen.getByLabelText('Seleccionar AAPL'))

    // Find delete selected button — text contains "Borrar seleccionadas"
    const batchBtn = screen.getByText(/Borrar seleccionadas/)
    expect(batchBtn).not.toBeDisabled()
    fireEvent.click(batchBtn)

    expect(handlers.onClearStaleBatch).toHaveBeenCalledTimes(1)
    expect(handlers.onClearStaleBatch).toHaveBeenCalledWith(['AAPL'])
  })

  it('"Borrar seleccionadas" está deshabilitado cuando no hay selección', () => {
    const trade = makeSignal({ ticker: 'AAPL', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    render(<LiveTradeGrid trades={[trade]} staleSignalCount={1} />)

    const batchBtn = screen.getByText(/Borrar seleccionadas/)
    expect(batchBtn).toBeDisabled()
  })

  it('"Borrar todas" llama onClearAllStale', () => {
    const handlers = makeHandlers()
    render(<LiveTradeGrid staleSignalCount={2} {...handlers} />)

    fireEvent.click(screen.getByText(/Borrar todas las/))

    expect(handlers.onClearAllStale).toHaveBeenCalledTimes(1)
  })

  it('"Borrar seleccionadas" limpia la selección después de llamar al handler', () => {
    const trade = makeSignal({ ticker: 'AAPL', signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid trades={[trade]} staleSignalCount={1} {...handlers} />)

    fireEvent.click(screen.getByLabelText('Seleccionar AAPL'))
    fireEvent.click(screen.getByText(/Borrar seleccionadas/))

    // After batch delete, checkbox must be unchecked (selection cleared)
    expect(screen.getByLabelText('Seleccionar AAPL').checked).toBe(false)
  })

  it('señal EXECUTED no muestra checkbox aunque signalStale=true', () => {
    const trade = makeTrade({ ticker: 'AAPL', signalStale: true })
    render(<LiveTradeGrid trades={[trade]} staleSignalCount={0} />)
    expect(screen.queryByLabelText('Seleccionar AAPL')).toBeNull()
  })

  it('badge >30m visible en el status de señal stale no ejecutada', () => {
    const trade = makeSignal({ signalStale: true, tradeStatus: 'SIGNAL', exitedAt: '-' })
    render(<LiveTradeGrid trades={[trade]} />)
    expect(screen.getByText('>30m')).toBeTruthy()
  })
})

// ── Group 4: Posiciones EXTERNAL ──────────────────────────────────────────────

describe('LiveTradeGrid — Posiciones EXTERNAL', () => {

  it('renderiza fila EXTERNAL con badge "EXTERNAL"', () => {
    const ext = makeExternal({ ticker: 'TSLA' })
    render(<LiveTradeGrid externalPositions={[ext]} />)
    // The badge span with EXTERNAL text
    const badges = screen.getAllByText('EXTERNAL')
    expect(badges.length).toBeGreaterThanOrEqual(1)
  })

  it('muestra el avg_cost formateado de la posición externa', () => {
    const ext = makeExternal({ ticker: 'TSLA', avg_cost: 200.5 })
    render(<LiveTradeGrid externalPositions={[ext]} />)
    expect(screen.getByText('$200.50')).toBeTruthy()
  })

  it('muestra el ticker de la posición externa en la tabla', () => {
    const ext = makeExternal({ ticker: 'TSLA' })
    render(<LiveTradeGrid externalPositions={[ext]} />)
    expect(screen.getByText('TSLA')).toBeTruthy()
  })

  it('click "Close" en EXTERNAL — con confirm=true — llama onCloseExternal', async () => {
    window.confirm.mockReturnValue(true)
    const ext = makeExternal({ ticker: 'TSLA' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid externalPositions={[ext]} {...handlers} />)

    await act(async () => {
      fireEvent.click(screen.getByText('Close'))
    })

    expect(window.confirm).toHaveBeenCalledWith('Cerrar posición TSLA a mercado?')
    expect(handlers.onCloseExternal).toHaveBeenCalledWith('TSLA')
  })

  it('click "Close" en EXTERNAL — con confirm=false — NO llama onCloseExternal', async () => {
    window.confirm.mockReturnValue(false)
    const ext = makeExternal({ ticker: 'TSLA' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid externalPositions={[ext]} {...handlers} />)

    await act(async () => {
      fireEvent.click(screen.getByText('Close'))
    })

    expect(handlers.onCloseExternal).not.toHaveBeenCalled()
  })

  it('checkbox "14:50 ET" llama onScheduleClose1450 con el ticker', async () => {
    const ext = makeExternal({ ticker: 'TSLA' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid externalPositions={[ext]} {...handlers} />)

    const checkbox = screen.getByRole('checkbox', { name: /14:50 ET/ })
    await act(async () => {
      fireEvent.click(checkbox)
    })

    expect(handlers.onScheduleClose1450).toHaveBeenCalledWith('TSLA')
  })

  it('después de schedule exitoso muestra badge "14:50 ET" en lugar del checkbox', async () => {
    const ext = makeExternal({ ticker: 'TSLA' })
    const handlers = makeHandlers()
    render(<LiveTradeGrid externalPositions={[ext]} {...handlers} />)

    const checkbox = screen.getByRole('checkbox', { name: /14:50 ET/ })
    await act(async () => {
      fireEvent.click(checkbox)
    })

    // After scheduling, the badge badge-scheduled-1450 should appear
    expect(document.querySelector('.badge-scheduled-1450')).toBeTruthy()
    // Checkbox should no longer be present
    expect(screen.queryByRole('checkbox', { name: /14:50 ET/ })).toBeNull()
  })

  it('EXTERNAL contabiliza en el contador de records', () => {
    const ext = makeExternal({ ticker: 'TSLA' })
    render(<LiveTradeGrid externalPositions={[ext]} />)
    expect(screen.getByText('1 records')).toBeTruthy()
  })

  it('posición EXTERNAL muestra snapshot_timestamp en la columna "Exited at"', () => {
    const ext = makeExternal({ ticker: 'TSLA', snapshot_timestamp: '14:30:00' })
    render(<LiveTradeGrid externalPositions={[ext]} />)
    expect(screen.getByText('14:30:00')).toBeTruthy()
  })

  it('Click "Close" muestra texto "Cerrando…" mientras la promesa está pendiente', async () => {
    window.confirm.mockReturnValue(true)
    let resolveClose
    const handlers = makeHandlers({
      onCloseExternal: vi.fn(() => new Promise((res) => { resolveClose = res })),
    })
    const ext = makeExternal({ ticker: 'TSLA' })
    render(<LiveTradeGrid externalPositions={[ext]} {...handlers} />)

    // Start the close — don't await, so we can inspect intermediate state
    act(() => {
      fireEvent.click(screen.getByText('Close'))
    })

    // While pending, button text should change to "Cerrando…"
    expect(screen.getByText('Cerrando…')).toBeTruthy()

    // Resolve and clean up
    await act(async () => {
      resolveClose()
      await Promise.resolve()
    })
  })
})

// ── Group 5: Scan Activity breakdown chips ────────────────────────────────────

describe('LiveTradeGrid — Scan activity chips', () => {

  it('NO muestra chip EN_COLA cuando scanning=false aunque haya filas EN_COLA', () => {
    const scan = [{ ticker: 'AAPL', status: 'EN_COLA' }]
    render(<LiveTradeGrid scanActivity={scan} scanning={false} />)
    expect(screen.queryByTestId('scan-chip-EN_COLA')).toBeNull()
  })

  it('muestra chip EN_COLA cuando scanning=true y hay filas EN_COLA', () => {
    const scan = [{ ticker: 'AAPL', status: 'EN_COLA' }]
    render(<LiveTradeGrid scanActivity={scan} scanning={true} />)
    expect(screen.getByTestId('scan-chip-EN_COLA')).toBeTruthy()
  })

  it('muestra chip OK cuando hay filas con status OK', () => {
    const scan = [{ ticker: 'AAPL', status: 'OK' }]
    render(<LiveTradeGrid scanActivity={scan} scanning={false} />)
    expect(screen.getByTestId('scan-chip-OK')).toBeTruthy()
  })

  it('muestra chip SIGNAL cuando hay filas con status SIGNAL', () => {
    const scan = [{ ticker: 'AAPL', status: 'SIGNAL' }]
    render(<LiveTradeGrid scanActivity={scan} />)
    expect(screen.getByTestId('scan-chip-SIGNAL')).toBeTruthy()
  })

  it('no muestra el breakdown cuando todos los chips tienen count=0', () => {
    render(<LiveTradeGrid scanActivity={[]} />)
    expect(screen.queryByTestId('scan-status-breakdown')).toBeNull()
  })
})
