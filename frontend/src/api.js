const API = '';

const handleResponse = async (r) => {
  if (!r.ok) {
    let msg = r.statusText;
    try {
      const data = await r.json();
      if (data && data.message) msg = data.message;
    } catch (e) { /* use statusText */ }
    throw new Error(`HTTP ${r.status}: ${msg}`);
  }
  return r.json();
}

// ===== Live Mode API =====

export const liveApi = {
  getStatus: () => fetch(`${API}/live-ui/status`).then(handleResponse),
  getSignals: () => fetch(`${API}/live-ui/signals`).then(handleResponse),
  getTickers: () => fetch(`${API}/live-ui/tickers`).then(handleResponse),
  getTwsStatus: () => fetch(`${API}/live-ui/tws-status`).then(handleResponse),
  getMarketStatus: () => fetch(`${API}/live-ui/market-status`).then(handleResponse),
  getScanActivity: () => fetch(`${API}/live-ui/scan-activity`).then(handleResponse),
  startScan: () => fetch(`${API}/live-ui/scan-now`, { method: 'POST' }).then(handleResponse),
  stopScan: () => fetch(`${API}/live-ui/stop-scan`, { method: 'POST' }).then(handleResponse),
  forceStop: () => fetch(`${API}/live-ui/force-stop`, { method: 'POST' }).then(handleResponse),
  toggleExtendedHours: () => fetch(`${API}/live-ui/toggle-extended-hours`, { method: 'POST' }).then(handleResponse),
  toggleAutoExecute: () => fetch(`${API}/live-ui/toggle-auto-execute`, { method: 'POST' }).then(handleResponse),
  toggleScheduler: () => fetch(`${API}/live-ui/toggle-scheduler`, { method: 'POST' }).then(handleResponse),
  setRisk: (pct) => fetch(`${API}/live-ui/set-risk?pct=${pct}`, { method: 'POST' }).then(handleResponse),
  setScanFilter: (filter, scope) => fetch(`${API}/live-ui/set-scan-filter?filter=${encodeURIComponent(filter)}&scope=${scope}`, { method: 'POST' }).then(handleResponse),
  setMaxConcurrent: (count) => fetch(`${API}/live-ui/set-max-concurrent?count=${count}`, { method: 'POST' }).then(handleResponse),
  toggleMockMarket: () => fetch(`${API}/live-ui/toggle-mock-market`, { method: 'POST' }).then(handleResponse),
  injectMockSignal: (ticker = 'SPY') => fetch(`${API}/live-ui/inject-mock-signal?ticker=${ticker}`, { method: 'POST' }).then(handleResponse),
  closeTrade: (ticker, price) => fetch(`${API}/live-ui/close-trade?ticker=${encodeURIComponent(ticker)}&price=${price}`, { method: 'POST' }).then(handleResponse),
  executeSignal: (ticker, direction, price, strategy) => fetch(`${API}/live-ui/execute-signal?ticker=${encodeURIComponent(ticker)}&direction=${direction}&price=${price}&strategy=${encodeURIComponent(strategy)}`, { method: 'POST' }).then(handleResponse),
  executeTrade: (ticker, direction, price, strategy) => fetch(`${API}/live-ui/execute-trade?ticker=${encodeURIComponent(ticker)}&direction=${direction}&price=${price}&strategy=${encodeURIComponent(strategy)}`, { method: 'POST' }).then(handleResponse),
  cancelTrade: (ticker, orderId) => fetch(`${API}/live-ui/cancel-trade?ticker=${encodeURIComponent(ticker)}&orderId=${orderId}`, { method: 'POST' }).then(handleResponse),
};

// ===== Backtest API =====

export const backtestApi = {
  runBacktest: (capital = 50000, risk = 0.02, tickerFilter = '', tickerScope = 'ALL') => {
    let url = `${API}/backtest-ui/run?initialCapital=${capital}&riskPct=${risk}&tickerScope=${encodeURIComponent(tickerScope)}`;
    if (tickerFilter && tickerFilter.trim()) url += `&tickerFilter=${encodeURIComponent(tickerFilter.trim())}`;
    return fetch(url, { method: 'POST' }).then(handleResponse);
  },
  isRunning: () => fetch(`${API}/backtest-ui/running`).then(handleResponse),
  stopBacktest: () => fetch(`${API}/backtest-ui/stop`, { method: 'POST' }).then(handleResponse),
  getCheckpoint: () => fetch(`${API}/backtest-ui/checkpoint`).then(handleResponse),
  clearCheckpoint: () => fetch(`${API}/backtest-ui/checkpoint/clear`, { method: 'POST' }).then(handleResponse),
  getMaxConcurrent: () => fetch(`${API}/backtest-ui/max-concurrent`).then(handleResponse),
  setMaxConcurrent: (count) => fetch(`${API}/backtest-ui/set-max-concurrent?count=${count}`, { method: 'POST' }).then(handleResponse),
  improveStrategy: (name) => fetch(`${API}/backtest-ui/improve/${name}`, { method: 'POST' }).then(handleResponse),
  retestStrategy: (name) => fetch(`${API}/backtest-ui/retest/${name}`, { method: 'POST' }).then(handleResponse),
};

// ===== Health API =====

export const healthApi = {
  getHealth: () => fetch(`${API}/actuator/health`).then(handleResponse),
  getLiveness: () => fetch(`${API}/actuator/health/liveness`).then(handleResponse),
  getReadiness: () => fetch(`${API}/actuator/health/readiness`).then(handleResponse),
};
