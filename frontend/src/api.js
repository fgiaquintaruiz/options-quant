const API = '';

export const handleResponse = async (r) => {
  if (!r.ok) {
    let msg = r.statusText;
    try {
      const data = await r.json();
      if (data && data.error) msg = data.error;
      else if (data && data.message) msg = data.message;
    } catch (e) { /* use statusText */ }
    throw new Error(`HTTP ${r.status}: ${msg}`);
  }
  return r.json();
}

const get      = (path)     => fetch(`${API}${path}`).then(handleResponse)
const post     = (path)     => fetch(`${API}${path}`, { method: 'POST' }).then(handleResponse)
const postQ    = (path, q)  => fetch(`${API}${path}?${q}`, { method: 'POST' }).then(handleResponse)
const postJson = (path, b)  => fetch(`${API}${path}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b),
}).then(handleResponse)
const putJson  = (path, b)  => fetch(`${API}${path}`, {
  method: 'PUT',  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b),
}).then(handleResponse)
const del      = (path)     => fetch(`${API}${path}`, { method: 'DELETE' }).then(handleResponse)

// ===== Live Mode API =====

export const liveApi = {
  getStatus:            () => get('/live-ui/status'),
  getSignals:           () => get('/live-ui/signals'),
  getTickers:           () => get('/live-ui/tickers'),
  getTwsStatus:         () => get('/live-ui/tws-status'),
  getMarketStatus:      () => get('/live-ui/market-status'),
  getScanActivity:      () => get('/live-ui/scan-activity'),
  getScanScores:        () => get('/live-ui/scan-scores'),
  getNewsTickers:       () => get('/live-ui/news-tickers'),
  startScan:            () => post('/live-ui/scan-now'),
  stopScan:             () => post('/live-ui/stop-scan'),
  forceStop:            () => post('/live-ui/force-stop'),
  toggleExtendedHours:  () => post('/live-ui/toggle-extended-hours'),
  toggleAutoExecute:    () => post('/live-ui/toggle-auto-execute'),
  toggleMacroFilter:    () => post('/live-ui/toggle-macro-filter'),
  toggleScheduler:      () => post('/live-ui/toggle-scheduler'),
  toggleMockMarket:     () => post('/live-ui/toggle-mock-market'),
  clearStaleSignals:    () => post('/live-ui/signals/clear-stale'),
  setRisk:          (pct)           => postQ('/live-ui/set-risk', `pct=${pct}`),
  setMaxConcurrent: (count)         => postQ('/live-ui/set-max-concurrent', `count=${count}`),
  injectMockSignal: (ticker = 'SPY')=> postQ('/live-ui/inject-mock-signal', `ticker=${ticker}`),
  setScanFilter: (filter, scope)    =>
    postQ('/live-ui/set-scan-filter', `filter=${encodeURIComponent(filter)}&scope=${scope}`),
  executeSignal: (ticker, direction, price, strategy) =>
    postQ('/live-ui/execute-signal', `ticker=${encodeURIComponent(ticker)}&direction=${direction}&price=${price}&strategy=${encodeURIComponent(strategy)}`),
  executeTrade: (ticker, direction, price, strategy) =>
    postQ('/live-ui/execute-trade', `ticker=${encodeURIComponent(ticker)}&direction=${direction}&price=${price}&strategy=${encodeURIComponent(strategy)}`),
  cancelTrade: (ticker, orderId) =>
    postQ('/live-ui/cancel-trade', `ticker=${encodeURIComponent(ticker)}&orderId=${orderId}`),
  /** Sends market close; tpOrderId/slOrderId cancel bracket legs if provided. */
  closeTrade: (ticker, price, tpOrderId = null, slOrderId = null) => {
    const p = new URLSearchParams({ ticker, price: String(price) });
    if (tpOrderId) p.append('tpOrderId', tpOrderId);
    if (slOrderId) p.append('slOrderId', slOrderId);
    return postQ('/live-ui/close-trade', p);
  },
  deleteSignal: (ticker) =>
    del(`/live-ui/signal?ticker=${encodeURIComponent(ticker)}`),
  batchDeleteSignals: (tickers) =>
    postJson('/live-ui/signals/batch-delete', Array.isArray(tickers) ? tickers : []),
};

// ===== Backtest API =====

export const backtestApi = {
  runBacktest: (capital = 50000, risk = 0.02, tickerFilter = '', tickerScope = 'ALL') => {
    let q = `initialCapital=${capital}&riskPct=${risk}&tickerScope=${encodeURIComponent(tickerScope)}`;
    if (tickerFilter && tickerFilter.trim()) q += `&tickerFilter=${encodeURIComponent(tickerFilter.trim())}`;
    return postQ('/backtest-ui/run', q);
  },
  isRunning:       () => get('/backtest-ui/running'),
  stopBacktest:    () => post('/backtest-ui/stop'),
  getCheckpoint:   () => get('/backtest-ui/checkpoint'),
  clearCheckpoint: () => post('/backtest-ui/checkpoint/clear'),
  getMaxConcurrent:() => get('/backtest-ui/max-concurrent'),
  setMaxConcurrent:(count) => postQ('/backtest-ui/set-max-concurrent', `count=${count}`),
  getScheduler:    () => get('/backtest-ui/scheduler'),
  postScheduler:   (body) => postJson('/backtest-ui/scheduler', body ?? {}),
  /** body: GridSearchRequest — axes (tp/sl multiplier deltas), optional walkForward* fields. */
  gridSearch:      (body) => postJson('/backtest-ui/grid-search', body),
  /** body: PromoteRiskRequest — dryRun=true for preview; promotes deltas to ticker-memory as absolute ATR multipliers. */
  promoteRiskParams:(body) => postJson('/backtest-ui/promote-risk-params', body),
  getTickerMemoryRiskProfiles: () => get('/backtest-ui/ticker-memory-profiles'),
  improveStrategy: (name, ticker) => {
    let path = `/backtest-ui/improve/${encodeURIComponent(name)}`;
    if (ticker != null && String(ticker).trim()) path += `?ticker=${encodeURIComponent(String(ticker).trim())}`;
    return post(path);
  },
  /** matchLastRun=true reuses last UI backtest tickers/dates (fast); deltas shift ATR multipliers for this run only. */
  retestStrategy: (name, capital = 50000, risk = 0.02, matchLastRun = true, tpMultiplierDelta = 0, slMultiplierDelta = 0, ticker = null) => {
    const q = new URLSearchParams({
      initialCapital: String(capital),
      riskPct: String(risk),
      matchLastRun: String(matchLastRun),
      tpMultiplierDelta: String(tpMultiplierDelta),
      slMultiplierDelta: String(slMultiplierDelta),
    });
    if (ticker != null && String(ticker).trim()) q.append('ticker', String(ticker).trim());
    return postQ(`/backtest-ui/retest/${encodeURIComponent(name)}`, q);
  },
  deleteTickerMemoryRiskProfile: (ticker, strategy) =>
    del(`/backtest-ui/ticker-memory-profile?ticker=${encodeURIComponent(ticker)}&strategy=${encodeURIComponent(strategy)}`),
};

// ===== Ticker universe / HOT / fundamentals =====

export const tickerConfigApi = {
  get: () => get('/api/ticker-config'),
  put: (body) => putJson('/api/ticker-config', body ?? {}),
  deleteSymbol: (symbol) => del(`/api/ticker-config/symbol/${encodeURIComponent(symbol)}`),
  validate: (symbol) => get(`/api/ticker-config/validate?symbol=${encodeURIComponent(symbol)}`),
  getHotTickerCount: () => get('/api/ticker-config/hot-ticker-count'),
  setHotTickerCount: (count) => fetch(`/api/ticker-config/hot-ticker-count?count=${encodeURIComponent(count)}`, {
    method: 'PUT',
  }).then(handleResponse),
};

// ===== Analytics sidecar (Python :8001) =====

const ANALYTICS = 'http://localhost:8001'
const analyticsGet = (path) => fetch(`${ANALYTICS}${path}`).then(handleResponse)

export const analyticsApi = {
  getTickerInfo: (ticker) => analyticsGet(`/api/v1/ticker-info/${encodeURIComponent(ticker)}`),
}

// ===== Replay + Account Mode API =====

export const accountApi = {
  getMode: () => get('/live-ui/account-mode'),
}

export const replayApi = {
  start:    (date, speed)  => postQ('/live-ui/replay/start', `date=${encodeURIComponent(date)}&speed=${speed}`),
  stop:     ()             => post('/live-ui/replay/stop'),
  setSpeed: (speed)        => fetch(`${API}/live-ui/replay/speed?speed=${speed}`, { method: 'PUT' }).then(handleResponse),
  status:   ()             => get('/live-ui/replay/status'),
}

// ===== External Positions API =====

export const externalPositionsApi = {
  getExternalPositions: () =>
    get('/live-ui/external-positions'),

  closeExternalPosition: (ticker) =>
    post(`/live-ui/external-positions/${encodeURIComponent(ticker)}/close`),

  scheduleClose1450: (ticker) =>
    post(`/live-ui/external-positions/${encodeURIComponent(ticker)}/schedule-close-1450`),
}

// ===== Health API =====

export const healthApi = {
  getHealth:    () => get('/actuator/health'),
  getLiveness:  () => get('/actuator/health/liveness'),
  getReadiness: () => get('/actuator/health/readiness'),
};
