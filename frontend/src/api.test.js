import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { handleResponse, liveApi, backtestApi, tickerConfigApi, healthApi, replayApi, externalPositionsApi, analyticsApi, accountApi, strategyConfigApi } from './api.js'

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

  it('handleResponse falls back to statusText when JSON body has no error/message', async () => {
    const r = {
      ok: false,
      status: 422,
      statusText: 'Unprocessable',
      json: () => Promise.resolve({ code: 42 }),
    }
    await expect(handleResponse(r)).rejects.toThrow('HTTP 422: Unprocessable')
  })

  it('liveApi.getSignals GETs /live-ui/signals', async () => {
    await liveApi.getSignals()
    expect(fetch).toHaveBeenCalledWith('/live-ui/signals')
  })

  it('liveApi.getTickers GETs /live-ui/tickers', async () => {
    await liveApi.getTickers()
    expect(fetch).toHaveBeenCalledWith('/live-ui/tickers')
  })

  it('liveApi.getTwsStatus GETs /live-ui/tws-status', async () => {
    await liveApi.getTwsStatus()
    expect(fetch).toHaveBeenCalledWith('/live-ui/tws-status')
  })

  it('liveApi.getMarketStatus GETs /live-ui/market-status', async () => {
    await liveApi.getMarketStatus()
    expect(fetch).toHaveBeenCalledWith('/live-ui/market-status')
  })

  it('liveApi.getScanActivity GETs /live-ui/scan-activity', async () => {
    await liveApi.getScanActivity()
    expect(fetch).toHaveBeenCalledWith('/live-ui/scan-activity')
  })

  it('liveApi.getNewsTickers GETs /live-ui/news-tickers', async () => {
    await liveApi.getNewsTickers()
    expect(fetch).toHaveBeenCalledWith('/live-ui/news-tickers')
  })

  it('liveApi.startScan POSTs /live-ui/scan-now', async () => {
    await liveApi.startScan()
    expect(fetch).toHaveBeenCalledWith('/live-ui/scan-now', { method: 'POST' })
  })

  it('liveApi.stopScan POSTs /live-ui/stop-scan', async () => {
    await liveApi.stopScan()
    expect(fetch).toHaveBeenCalledWith('/live-ui/stop-scan', { method: 'POST' })
  })

  it('liveApi.toggleExtendedHours POSTs /live-ui/toggle-extended-hours', async () => {
    await liveApi.toggleExtendedHours()
    expect(fetch).toHaveBeenCalledWith('/live-ui/toggle-extended-hours', { method: 'POST' })
  })

  it('liveApi.toggleAutoExecute POSTs /live-ui/toggle-auto-execute', async () => {
    await liveApi.toggleAutoExecute()
    expect(fetch).toHaveBeenCalledWith('/live-ui/toggle-auto-execute', { method: 'POST' })
  })

  it('liveApi.toggleMacroFilter POSTs /live-ui/toggle-macro-filter', async () => {
    await liveApi.toggleMacroFilter()
    expect(fetch).toHaveBeenCalledWith('/live-ui/toggle-macro-filter', { method: 'POST' })
  })

  it('liveApi.toggleScheduler POSTs /live-ui/toggle-scheduler', async () => {
    await liveApi.toggleScheduler()
    expect(fetch).toHaveBeenCalledWith('/live-ui/toggle-scheduler', { method: 'POST' })
  })

  it('liveApi.toggleMockMarket POSTs /live-ui/toggle-mock-market', async () => {
    await liveApi.toggleMockMarket()
    expect(fetch).toHaveBeenCalledWith('/live-ui/toggle-mock-market', { method: 'POST' })
  })

  it('liveApi.clearStaleSignals POSTs /live-ui/signals/clear-stale', async () => {
    await liveApi.clearStaleSignals()
    expect(fetch).toHaveBeenCalledWith('/live-ui/signals/clear-stale', { method: 'POST' })
  })

  it('liveApi.setRisk POSTs with pct query param', async () => {
    await liveApi.setRisk(5)
    expect(fetch.mock.calls[0][0]).toContain('pct=5')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('liveApi.setMaxConcurrent POSTs with count query param', async () => {
    await liveApi.setMaxConcurrent(3)
    expect(fetch.mock.calls[0][0]).toContain('count=3')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('liveApi.injectMockSignal defaults to SPY ticker', async () => {
    await liveApi.injectMockSignal()
    expect(fetch.mock.calls[0][0]).toContain('ticker=SPY')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('liveApi.setScanFilter includes filter and scope in query', async () => {
    await liveApi.setScanFilter('hot', 'HOT')
    const url = fetch.mock.calls[0][0]
    expect(url).toContain('filter=hot')
    expect(url).toContain('scope=HOT')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('liveApi.executeTrade POSTs to /live-ui/execute-trade', async () => {
    await liveApi.executeTrade('SPY', 'CALL', 400, 'x')
    expect(fetch.mock.calls[0][0]).toContain('/live-ui/execute-trade')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('liveApi.cancelTrade POSTs with ticker and orderId', async () => {
    await liveApi.cancelTrade('SPY', 123)
    const url = fetch.mock.calls[0][0]
    expect(url).toContain('ticker=SPY')
    expect(url).toContain('orderId=123')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('liveApi.closeTrade without optional order ids omits tpOrderId and slOrderId', async () => {
    await liveApi.closeTrade('AAPL', 200)
    const url = fetch.mock.calls[0][0]
    expect(url).toContain('ticker=')
    expect(url).toContain('price=')
    expect(url).not.toContain('tpOrderId')
    expect(url).not.toContain('slOrderId')
  })

  it('liveApi.deleteSignal DELETEs with ticker in URL', async () => {
    await liveApi.deleteSignal('AAPL')
    expect(fetch.mock.calls[0][0]).toContain('/live-ui/signal?ticker=AAPL')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'DELETE' })
  })

  it('backtestApi.isRunning GETs /backtest-ui/running', async () => {
    await backtestApi.isRunning()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/running')
  })

  it('backtestApi.stopBacktest POSTs /backtest-ui/stop', async () => {
    await backtestApi.stopBacktest()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/stop', { method: 'POST' })
  })

  it('backtestApi.getCheckpoint GETs /backtest-ui/checkpoint', async () => {
    await backtestApi.getCheckpoint()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/checkpoint')
  })

  it('backtestApi.clearCheckpoint POSTs /backtest-ui/checkpoint/clear', async () => {
    await backtestApi.clearCheckpoint()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/checkpoint/clear', { method: 'POST' })
  })

  it('backtestApi.getMaxConcurrent GETs /backtest-ui/max-concurrent', async () => {
    await backtestApi.getMaxConcurrent()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/max-concurrent')
  })

  it('backtestApi.setMaxConcurrent POSTs with count query param', async () => {
    await backtestApi.setMaxConcurrent(4)
    expect(fetch.mock.calls[0][0]).toContain('count=4')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('backtestApi.getScheduler GETs /backtest-ui/scheduler', async () => {
    await backtestApi.getScheduler()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/scheduler')
  })

  it('backtestApi.getTickerMemoryRiskProfiles GETs /backtest-ui/ticker-memory-profiles', async () => {
    await backtestApi.getTickerMemoryRiskProfiles()
    expect(fetch).toHaveBeenCalledWith('/backtest-ui/ticker-memory-profiles')
  })

  it('backtestApi.runBacktest with default args omits tickerFilter', async () => {
    await backtestApi.runBacktest()
    expect(fetch.mock.calls[0][0]).not.toContain('tickerFilter=')
  })

  it('tickerConfigApi.get GETs /api/ticker-config', async () => {
    await tickerConfigApi.get()
    expect(fetch).toHaveBeenCalledWith('/api/ticker-config')
  })

  it('tickerConfigApi.deleteSymbol DELETEs with encoded symbol', async () => {
    await tickerConfigApi.deleteSymbol('TSLA')
    expect(fetch.mock.calls[0][0]).toContain('/api/ticker-config/symbol/TSLA')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'DELETE' })
  })

  it('tickerConfigApi.validate GETs with symbol query param', async () => {
    await tickerConfigApi.validate('AAPL')
    expect(fetch.mock.calls[0][0]).toContain('/api/ticker-config/validate?symbol=AAPL')
  })

  it('analyticsApi.getTickerInfo GETs from analytics base URL', async () => {
    await analyticsApi.getTickerInfo('NVDA')
    expect(fetch.mock.calls[0][0]).toBe('http://localhost:8001/api/v1/ticker-info/NVDA')
  })

  it('accountApi.getMode GETs /live-ui/account-mode', async () => {
    await accountApi.getMode()
    expect(fetch).toHaveBeenCalledWith('/live-ui/account-mode')
  })

  it('replayApi.start POSTs with date and speed', async () => {
    await replayApi.start('2024-01-15', 2)
    const url = fetch.mock.calls[0][0]
    expect(url).toContain('date=2024-01-15')
    expect(url).toContain('speed=2')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('replayApi.stop POSTs /live-ui/replay/stop', async () => {
    await replayApi.stop()
    expect(fetch).toHaveBeenCalledWith('/live-ui/replay/stop', { method: 'POST' })
  })

  it('replayApi.setSpeed PUTs with speed query param', async () => {
    await replayApi.setSpeed(4)
    const url = fetch.mock.calls[0][0]
    expect(url).toContain('/live-ui/replay/speed?speed=4')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'PUT' })
  })

  it('replayApi.status GETs /live-ui/replay/status', async () => {
    await replayApi.status()
    expect(fetch).toHaveBeenCalledWith('/live-ui/replay/status')
  })

  it('healthApi.getHealth GETs /actuator/health', async () => {
    await healthApi.getHealth()
    expect(fetch).toHaveBeenCalledWith('/actuator/health')
  })

  it('healthApi.getLiveness GETs /actuator/health/liveness', async () => {
    await healthApi.getLiveness()
    expect(fetch).toHaveBeenCalledWith('/actuator/health/liveness')
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

describe('strategyConfigApi', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([]),
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('findAll calls GET /api/strategy-config', async () => {
    await strategyConfigApi.findAll()
    expect(fetch).toHaveBeenCalledWith('/api/strategy-config')
  })

  it('findAll returns parsed JSON array', async () => {
    const data = [{ name: 'S1' }, { name: 'S2' }]
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(data),
    })
    const result = await strategyConfigApi.findAll()
    expect(result).toEqual(data)
  })

  it('update calls PATCH /api/strategy-config/{name}', async () => {
    await strategyConfigApi.update('MyStrategy', { enabled: true })
    expect(fetch.mock.calls[0][0]).toBe('/api/strategy-config/MyStrategy')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'PATCH' })
  })

  it('update sends correct JSON body with Content-Type header', async () => {
    const body = { enabled: false, riskPct: 0.01 }
    await strategyConfigApi.update('MyStrategy', body)
    expect(fetch).toHaveBeenCalledWith('/api/strategy-config/MyStrategy', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  })
})
