import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { handleResponse, liveApi, backtestApi, tickerConfigApi, healthApi, replayApi, externalPositionsApi } from './api.js'

describe('handleResponse', () => {
  it('parses JSON when ok', async () => {
    const r = { ok: true, json: () => Promise.resolve({ x: 1 }) }
    await expect(handleResponse(r)).resolves.toEqual({ x: 1 })
  })

  it('throws with backend error field', async () => {
    const r = {
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      json: () => Promise.resolve({ error: 'nope' }),
    }
    await expect(handleResponse(r)).rejects.toThrow('HTTP 400: nope')
  })

  it('throws with message field', async () => {
    const r = {
      ok: false,
      status: 500,
      statusText: 'Err',
      json: () => Promise.resolve({ message: 'm' }),
    }
    await expect(handleResponse(r)).rejects.toThrow('HTTP 500: m')
  })

  it('falls back to statusText when JSON parse fails', async () => {
    const r = {
      ok: false,
      status: 502,
      statusText: 'Bad Gateway',
      json: () => Promise.reject(new Error('bad json')),
    }
    await expect(handleResponse(r)).rejects.toThrow('HTTP 502: Bad Gateway')
  })
})

describe('API clients (fetch mocked)', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({}),
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('liveApi.getStatus hits /live-ui/status', async () => {
    await liveApi.getStatus()
    expect(fetch).toHaveBeenCalledWith('/live-ui/status')
  })

  it('liveApi.forceStop POSTs /live-ui/force-stop', async () => {
    await liveApi.forceStop()
    expect(fetch).toHaveBeenCalledWith('/live-ui/force-stop', { method: 'POST' })
  })

  it('liveApi.closeTrade builds query with optional order ids', async () => {
    await liveApi.closeTrade('SPY', 100.5, 'tp1', 'sl2')
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/live-ui/close-trade?ticker=SPY&price=100.5&tpOrderId=tp1&slOrderId=sl2'),
      { method: 'POST' }
    )
  })

  it('backtestApi.runBacktest adds tickerFilter when set', async () => {
    await backtestApi.runBacktest(10000, 0.01, 'AAPL', 'HOT')
    const url = fetch.mock.calls[0][0]
    expect(url).toContain('tickerFilter=AAPL')
    expect(url).toContain('tickerScope=HOT')
  })

  it('backtestApi.improveStrategy adds ticker query', async () => {
    await backtestApi.improveStrategy('s1', 'NVDA')
    expect(fetch.mock.calls[0][0]).toContain('/backtest-ui/improve/s1?ticker=NVDA')
  })

  it('backtestApi.retestStrategy omits ticker when null', async () => {
    await backtestApi.retestStrategy('x', 50000, 0.02, true, 0, 0, null)
    const u = fetch.mock.calls[0][0]
    expect(u).not.toContain('ticker=')
  })

  it('backtestApi.gridSearch posts JSON body', async () => {
    const body = { tickers: ['SPY'] }
    await backtestApi.gridSearch(body)
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/grid-search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  })

  it('backtestApi.postScheduler POSTs JSON and coalesces body to {}', async () => {
    await backtestApi.postScheduler({ enabled: true })
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/scheduler', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: true }),
    })
    await backtestApi.postScheduler(undefined)
    expect(fetch.mock.calls.pop()[1].body).toBe(JSON.stringify({}))
  })

  it('tickerConfigApi.put sends body', async () => {
    await tickerConfigApi.put({ symbols: [] })
    expect(fetch).toHaveBeenCalledWith('/api/ticker-config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbols: [] }),
    })
  })

  it('healthApi.getReadiness', async () => {
    await healthApi.getReadiness()
    expect(fetch).toHaveBeenCalledWith('/actuator/health/readiness')
  })

  it('backtestApi.improveStrategy without ticker', async () => {
    await backtestApi.improveStrategy('onlyName')
    expect(fetch.mock.calls[0][0]).toBe('/backtest-ui/improve/onlyName')
  })

  it('backtestApi.retestStrategy with ticker', async () => {
    await backtestApi.retestStrategy('s', 1, 0.1, false, 0.1, 0.2, 'QQQ')
    expect(fetch.mock.calls[0][0]).toContain('ticker=QQQ')
  })

  it('backtestApi.promoteRiskParams and deleteTickerMemoryRiskProfile', async () => {
    await backtestApi.promoteRiskParams({ dryRun: true })
    expect(fetch).toHaveBeenCalledWith(
      '/backtest-ui/promote-risk-params',
      expect.objectContaining({ method: 'POST' })
    )
    await backtestApi.deleteTickerMemoryRiskProfile('A', 'strat')
    expect(fetch.mock.calls.pop()[0]).toContain('/backtest-ui/ticker-memory-profile?ticker=A')
  })

  it('liveApi batchDeleteSignals and executeSignal', async () => {
    await liveApi.batchDeleteSignals(['A', 'B'])
    expect(fetch.mock.calls[0][0]).toBe('/live-ui/signals/batch-delete')
    await liveApi.executeSignal('SPY', 'CALL', 400, 'x')
    expect(fetch.mock.calls.pop()[0]).toContain('/live-ui/execute-signal')
  })

  it('liveApi.batchDeleteSignals coerces non-array to empty list body', async () => {
    await liveApi.batchDeleteSignals(null)
    expect(fetch.mock.calls[0][1].body).toBe(JSON.stringify([]))
  })

  it('tickerConfigApi.put coalesces null body to {}', async () => {
    await tickerConfigApi.put(null)
    expect(fetch.mock.calls[0][1].body).toBe(JSON.stringify({}))
  })

  it('liveApi.getScanScores GETs /live-ui/scan-scores', async () => {
    await liveApi.getScanScores()
    expect(fetch).toHaveBeenCalledWith('/live-ui/scan-scores')
  })

  it('tickerConfigApi.getHotTickerCount GETs correct endpoint', async () => {
    await tickerConfigApi.getHotTickerCount()
    expect(fetch).toHaveBeenCalledWith('/api/ticker-config/hot-ticker-count')
  })

  it('tickerConfigApi.setHotTickerCount PUTs with count query param', async () => {
    await tickerConfigApi.setHotTickerCount(15)
    expect(fetch).toHaveBeenCalledWith(
      '/api/ticker-config/hot-ticker-count?count=15',
      { method: 'PUT' }
    )
  })
})

describe('externalPositionsApi', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({}),
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // ── getExternalPositions ────────────────────────────────────────────────────

  it('getExternalPositions GETs /live-ui/external-positions', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ positions: [{ ticker: 'AAPL' }] }),
    })
    const result = await externalPositionsApi.getExternalPositions()
    expect(fetch).toHaveBeenCalledWith('/live-ui/external-positions')
    expect(result).toEqual({ positions: [{ ticker: 'AAPL' }] })
  })

  it('getExternalPositions throws on 503 with correct message', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      json: () => Promise.resolve({ error: 'TWS down' }),
    })
    await expect(externalPositionsApi.getExternalPositions()).rejects.toThrow('HTTP 503: TWS down')
  })

  // ── closeExternalPosition ───────────────────────────────────────────────────

  it('closeExternalPosition POSTs /live-ui/external-positions/SPY/close', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ message: 'ok', orderId: 42 }),
    })
    const result = await externalPositionsApi.closeExternalPosition('SPY')
    expect(fetch).toHaveBeenCalledWith('/live-ui/external-positions/SPY/close', { method: 'POST' })
    expect(result).toEqual({ message: 'ok', orderId: 42 })
  })

  it('closeExternalPosition encodes special chars in ticker', async () => {
    await externalPositionsApi.closeExternalPosition('BRK B')
    expect(fetch.mock.calls[0][0]).toContain('/live-ui/external-positions/BRK%20B/close')
  })

  it('closeExternalPosition throws on 404 (position no longer exists)', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: () => Promise.resolve({ error: 'position not found' }),
    })
    await expect(externalPositionsApi.closeExternalPosition('SPY')).rejects.toThrow('HTTP 404: position not found')
  })

  it('closeExternalPosition throws on 503 (TWS down)', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      json: () => Promise.resolve({ error: 'TWS not connected' }),
    })
    await expect(externalPositionsApi.closeExternalPosition('SPY')).rejects.toThrow('HTTP 503: TWS not connected')
  })

  // ── scheduleClose1450 ───────────────────────────────────────────────────────

  it('scheduleClose1450 POSTs /live-ui/external-positions/AAPL/schedule-close-1450', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ orderId: 99, message: 'scheduled' }),
    })
    const result = await externalPositionsApi.scheduleClose1450('AAPL')
    expect(fetch).toHaveBeenCalledWith('/live-ui/external-positions/AAPL/schedule-close-1450', { method: 'POST' })
    expect(result).toEqual({ orderId: 99, message: 'scheduled' })
  })

  it('scheduleClose1450 throws on 503', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      json: () => Promise.resolve({ error: 'TWS not connected' }),
    })
    await expect(externalPositionsApi.scheduleClose1450('AAPL')).rejects.toThrow('HTTP 503: TWS not connected')
  })

  it('scheduleClose1450 throws on 409 (already scheduled)', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: false,
      status: 409,
      statusText: 'Conflict',
      json: () => Promise.resolve({ error: 'already scheduled' }),
    })
    await expect(externalPositionsApi.scheduleClose1450('AAPL')).rejects.toThrow('HTTP 409: already scheduled')
  })
})
