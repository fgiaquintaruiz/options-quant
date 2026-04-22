import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { handleResponse, liveApi, backtestApi, tickerConfigApi, healthApi } from './api.js'

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
})
