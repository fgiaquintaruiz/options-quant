const API = '';

// ===== Live Mode API =====

export const liveApi = {
  getStatus: () => fetch(`${API}/live-ui/status`).then(r => r.json()),
  getSignals: () => fetch(`${API}/live-ui/signals`).then(r => r.json()),
  getTickers: () => fetch(`${API}/live-ui/tickers`).then(r => r.json()),
  getTwsStatus: () => fetch(`${API}/live-ui/tws-status`).then(r => r.json()),
  startScan: () => fetch(`${API}/live-ui/scan-now`, { method: 'POST' }).then(r => r.json()),
  stopScan: () => fetch(`${API}/live-ui/stop-scan`, { method: 'POST' }).then(r => r.json()),
  toggleExtendedHours: () => fetch(`${API}/live-ui/toggle-extended-hours`, { method: 'POST' }).then(r => r.json()),
};

// ===== Backtest API =====

export const backtestApi = {
  runBacktest: (capital = 50000, risk = 0.02) =>
    fetch(`${API}/backtest-ui/run?initialCapital=${capital}&riskPct=${risk}`, { method: 'POST' }).then(r => r.json()),
  isRunning: () => fetch(`${API}/backtest-ui/running`).then(r => r.json()),
  stopBacktest: () => fetch(`${API}/backtest-ui/stop`, { method: 'POST' }).then(r => r.json()),
  getCheckpoint: () => fetch(`${API}/backtest-ui/checkpoint`).then(r => r.json()),
  clearCheckpoint: () => fetch(`${API}/backtest-ui/checkpoint/clear`, { method: 'POST' }).then(r => r.json()),
  improveStrategy: (name) => fetch(`${API}/backtest-ui/improve/${name}`, { method: 'POST' }).then(r => r.json()),
  retestStrategy: (name) => fetch(`${API}/backtest-ui/retest/${name}`, { method: 'POST' }).then(r => r.json()),
};

// ===== Health API =====

export const healthApi = {
  getHealth: () => fetch(`${API}/actuator/health`).then(r => r.json()),
  getLiveness: () => fetch(`${API}/actuator/health/liveness`).then(r => r.json()),
  getReadiness: () => fetch(`${API}/actuator/health/readiness`).then(r => r.json()),
};
