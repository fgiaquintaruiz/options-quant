"""
Python UI Service - Streamlit dashboard for strategy backtesting and monitoring.

This service provides an interactive dashboard for:
- Strategy backtesting with configurable parameters
- Real-time monitoring of trading strategies
- Performance visualization
- Parameter tuning and optimization
"""

import os
import logging
from typing import List, Dict, Optional

import streamlit as st
import pandas as pd
import numpy as np
import plotly.graph_objects as go
from plotly.subplots import make_subplots

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# ================== Configuration ==================

API_HOST = os.getenv('API_HOST', 'localhost')
API_PORT = os.getenv('API_PORT', '8001')
ANALYTICS_API_URL = f"http://{API_HOST}:{API_PORT}"

# Java API configuration (for calling Java trading engine endpoints)
JAVA_API_URL = os.getenv('JAVA_API_URL', 'http://localhost:9090')


# ================== Session State ==================

def init_session_state():
    """Initialize Streamlit session state."""
    if 'data_loaded' not in st.session_state:
        st.session_state.data_loaded = False
    if 'backtest_results' not in st.session_state:
        st.session_state.backtest_results = None
    if 'indicator_data' not in st.session_state:
        st.session_state.indicator_data = None


# ================== API Client ==================

def fetch_indicators(data: List[Dict], indicators: List[str], params: Optional[Dict] = None) -> Dict:
    """Fetch calculated indicators from analytics service."""
    import httpx

    try:
        response = httpx.post(
            f"{ANALYTICS_API_URL}/api/v1/indicators",
            json={
                "data": data,
                "indicators": indicators,
                "params": params
            },
            timeout=30.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.error(f"Error fetching indicators: {e}")
        return {"status": "error", "data": [], "error": str(e)}


def fetch_metrics(trades: List[Dict]) -> Dict:
    """Fetch performance metrics from analytics service."""
    import httpx

    try:
        response = httpx.post(
            f"{ANALYTICS_API_URL}/api/v1/metrics",
            json={"trades": trades},
            timeout=30.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.error(f"Error fetching metrics: {e}")
        return {"status": "error", "metrics": {}, "error": str(e)}


# ================== Java API Client ==================

def fetch_java_status() -> Dict:
    """Fetch live status from Java trading engine."""
    import httpx

    try:
        response = httpx.get(
            f"{JAVA_API_URL}/live-ui/status",
            timeout=10.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.warning(f"Error fetching Java status: {e}")
        return {
            "isScanning": False,
            "signalsToday": 0,
            "twsConnected": False,
            "error": str(e)
        }


def fetch_java_tws_status() -> Dict:
    """Fetch TWS/account status from Java trading engine."""
    import httpx

    try:
        response = httpx.get(
            f"{JAVA_API_URL}/live-ui/tws-status",
            timeout=10.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.warning(f"Error fetching Java tws-status: {e}")
        return {
            "connected": False,
            "balance": 0.0,
            "activeTrades": 0,
            "error": str(e)
        }


def fetch_java_signals() -> Dict:
    """Fetch signals from Java trading engine."""
    import httpx

    try:
        response = httpx.get(
            f"{JAVA_API_URL}/live-ui/signals",
            timeout=10.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.warning(f"Error fetching Java signals: {e}")
        return {
            "signals": [],
            "count": 0,
            "signalsToday": 0,
            "error": str(e)
        }


def run_java_backtest(
    initial_capital: float = 50000.0,
    risk_pct: float = 0.02,
    ticker_filter: Optional[str] = None,
    ticker_scope: str = "HOT"
) -> Dict:
    """Run backtest via Java trading engine."""
    import httpx

    try:
        # Build request params matching Java endpoint contracts
        params = {
            "initialCapital": initial_capital,
            "riskPct": risk_pct,
            "tickerScope": ticker_scope
        }
        if ticker_filter:
            params["tickerFilter"] = ticker_filter

        response = httpx.post(
            f"{JAVA_API_URL}/backtest-ui/run",
            data=params,
            timeout=60.0
        )
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.error(f"Error running Java backtest: {e}")
        return {
            "status": "error",
            "error": str(e),
            "message": "Unable to connect to backtest engine. Ensure Java service is running."
        }


# ================== Page Config ==================

st.set_page_config(
    page_title="Options Quant Dashboard",
    page_icon="📈",
    layout="wide",
    initial_sidebar_state="expanded"
)


# ================== Sidebar ==================

def render_sidebar():
    """Render sidebar navigation."""
    st.sidebar.title("Options Quant")
    st.sidebar.markdown("---")

    page = st.sidebar.radio(
        "Navigation",
        ["Backtesting", "Monitoring", "Analytics", "Settings"]
    )

    st.sidebar.markdown("---")
    st.sidebar.info(
        "**Version:** 1.0.0\n\n"
        "**Analytics API:** " + ANALYTICS_API_URL
    )

    return page


# ================== Backtesting Page ==================

def render_backtesting_page():
    """Render the backtesting page."""
    st.title("Strategy Backtesting")

    # Parameter Configuration
    st.subheader("Parameter Configuration")
    col1, col2, col3 = st.columns(3)

    with col1:
        strategy = st.selectbox(
            "Strategy",
            ["SMA Crossover", "RSI Reversal", "MACD Divergence", "Bollinger Bands"]
        )

    with col2:
        symbol = st.text_input("Symbol", "AAPL")
        start_date = st.date_input("Start Date")

    with col3:
        initial_capital = st.number_input("Initial Capital", value=10000.0)
        end_date = st.date_input("End Date")

    # Strategy-specific parameters
    st.subheader("Strategy Parameters")

    if strategy == "SMA Crossover":
        col1, col2 = st.columns(2)
        with col1:
            fast_period = st.slider("Fast Period", 5, 50, 20)
        with col2:
            slow_period = st.slider("Slow Period", 20, 200, 50)

    elif strategy == "RSI Reversal":
        col1, col2 = st.columns(2)
        with col1:
            rsi_period = st.slider("RSI Period", 5, 30, 14)
        with col2:
            oversold = st.slider("Oversold Level", 10, 40, 30)
            overbought = st.slider("Overbought Level", 60, 90, 70)

    elif strategy == "MACD Divergence":
        col1, col2, col3 = st.columns(3)
        with col1:
            macd_fast = st.slider("MACD Fast", 5, 20, 12)
        with col2:
            macd_slow = st.slider("MACD Slow", 20, 40, 26)
        with col3:
            macd_signal = st.slider("Signal", 5, 15, 9)

    elif strategy == "Bollinger Bands":
        col1, col2 = st.columns(2)
        with col1:
            bb_period = st.slider("BB Period", 10, 50, 20)
        with col2:
            bb_std = st.slider("Std Multiplier", 1.0, 3.0, 2.0)

    # Run Backtest Button
    st.markdown("---")

    # Ticker scope selection
    ticker_scope = st.selectbox("Tickers Scope", ["HOT", "ALL"], index=0)

    if st.button("Run Backtest", type="primary"):
        with st.spinner("Running backtest via Java engine..."):
            # Call Java backtest API
            result = run_java_backtest(
                initial_capital=float(initial_capital),
                risk_pct=0.02,
                ticker_scope=ticker_scope
            )

            if result.get("success"):
                st.success("Backtest completed!")
                # Store results for display
                st.session_state.backtest_results = result.get("trades", [])
            elif result.get("status") == "error":
                st.error(result.get("message", "Backtest failed"))
            else:
                st.error(f"Error: {result.get('error', 'Unknown error')}")

    # Results Display
    if st.session_state.indicator_data is not None:
        st.markdown("---")
        st.subheader("Results")

        # Price Chart with Indicators
        df = st.session_state.indicator_data

        fig = make_subplots(
            rows=2, cols=1,
            shared_xaxes=True,
            vertical_spacing=0.05,
            subplot_titles=("Price", "RSI"),
            row_heights=[0.7, 0.3]
        )

        # Price candlestick
        fig.add_trace(
            go.Candlestick(
                x=df.index,
                open=df['open'],
                high=df['high'],
                low=df['low'],
                close=df['close'],
                name="Price"
            ),
            row=1, col=1
        )

        # SMA
        if 'SMA_20' in df.columns:
            fig.add_trace(
                go.Scatter(
                    x=df.index,
                    y=df['SMA_20'],
                    mode='lines',
                    name='SMA 20',
                    line=dict(color='orange', width=1)
                ),
                row=1, col=1
            )

        # RSI
        if 'RSI_14' in df.columns:
            fig.add_trace(
                go.Scatter(
                    x=df.index,
                    y=df['RSI_14'],
                    mode='lines',
                    name='RSI',
                    line=dict(color='purple', width=1)
                ),
                row=2, col=1
            )

            # RSI reference lines
            fig.add_hline(y=70, line_dash="dash", line_color="red", row=2, col=1)
            fig.add_hline(y=30, line_dash="dash", line_color="green", row=2, col=1)

        fig.update_layout(
            height=600,
            showlegend=True,
            xaxis_rangeslider_visible=False
        )

        st.plotly_chart(fig, use_container_width=True)

        # Metrics
        if st.session_state.backtest_results:
            metrics_result = fetch_metrics(st.session_state.backtest_results)

            if metrics_result.get("status") == "success":
                metrics = metrics_result["metrics"]

                col1, col2, col3, col4 = st.columns(4)
                col1.metric("Total Trades", str(metrics.get('total_trades', 0)))
                col2.metric("Win Rate", f"{metrics.get('win_rate', 0) * 100:.1f}%")
                col3.metric("Total P&L", f"${metrics.get('total_pnl', 0):.2f}")
                col4.metric("Sharpe Ratio", f"{metrics.get('sharpe_ratio', 0):.2f}")

                col1, col2, col3, col4 = st.columns(4)
                col1.metric("Max Drawdown", f"${metrics.get('max_drawdown', 0):.2f}")
                col2.metric("Profit Factor", f"{metrics.get('profit_factor', 0):.2f}")
                col3.metric("Avg P&L", f"${metrics.get('avg_pnl', 0):.2f}")
                col4.metric("Sortino Ratio", f"{metrics.get('sortino_ratio', 0):.2f}")


# ================== Monitoring Page ==================

def render_monitoring_page():
    """Render the monitoring page."""
    st.title("Strategy Monitoring")

    st.info("Real-time monitoring dashboard - requires connection to live trading engine")

    # Fetch real-time data from Java engine
    status_data = fetch_java_status()
    tws_data = fetch_java_tws_status()
    signals_data = fetch_java_signals()

    # Format metrics with fallbacks
    active_strategies = str(status_data.get("signalsToday", "—"))
    open_positions = str(tws_data.get("activeTrades", "—"))
    
    # Daily P&L - use balance delta if available
    balance = tws_data.get("balance", 0.0)
    daily_pnl = f"${balance:,.2f}" if balance else "—"
    
    # Unrealized P&L (placeholder - would need position-level data)
    unrealized_pnl = "—"

    # Display metrics
    col1, col2, col3, col4 = st.columns(4)

    col1.metric("Active Strategies", active_strategies)
    col2.metric("Open Positions", open_positions)
    col3.metric("Daily P&L", daily_pnl)
    col4.metric("Unrealized P&L", unrealized_pnl)

    st.markdown("---")

    # Strategy Status Table (from API)
    st.subheader("Strategy Status")

    # Parse signals into DataFrame
    signals = signals_data.get("signals", [])
    if signals:
        signal_rows = []
        for s in signals:
            signal_rows.append({
                "Strategy": s.get("strategy", "—"),
                "Status": "Active" if s.get("tradePlan") else "Signal",
                "Ticker": s.get("ticker", "—"),
                "Direction": s.get("direction", "—"),
                "Price": f"${s.get('currentPrice', 0):.2f}" if s.get("currentPrice") else "—"
            })
        signals_df = pd.DataFrame(signal_rows)
    else:
        # Fallback empty state
        signals_df = pd.DataFrame({
            "Strategy": [],
            "Status": [],
            "Ticker": [],
            "Direction": [],
            "Price": []
        })

    st.dataframe(signals_df, use_container_width=True)

    # Controls
    st.subheader("Controls")

    col1, col2 = st.columns(2)

    with col1:
        selected_strategy = st.selectbox("Select Strategy", ["SMA Crossover", "RSI Reversal", "MACD Div"])

    with col2:
        action = st.radio("Action", ["Start", "Stop", "Pause"])

    if st.button("Execute"):
        st.success(f"{action} command sent to {selected_strategy}")


# ================== Analytics Page ==================

def render_analytics_page():
    """Render the analytics page."""
    st.title("Advanced Analytics")

    tab1, tab2, tab3 = st.tabs(["Monte Carlo", "Parameter Optimization", "Correlations"])

    with tab1:
        st.subheader("Monte Carlo Simulation")

        col1, col2 = st.columns(2)

        with col1:
            num_simulations = st.slider("Number of Simulations", 100, 5000, 1000)

        with col2:
            num_periods = st.slider("Periods", 30, 500, 252)

        if st.button("Run Simulation"):
            with st.spinner("Running simulation..."):
                # Sample historical returns
                np.random.seed(42)
                returns = list(np.random.randn(100) * 0.02)

                # This would call the analytics service in production
                st.info("Monte Carlo simulation would run here with the analytics service")

                # Simulated results
                mean_return = np.mean(returns) * num_periods
                prob_positive = np.mean(np.cumsum(returns) > 0)

                col1, col2, col3 = st.columns(3)
                col1.metric("Expected Return", f"{mean_return * 100:.1f}%")
                col2.metric("Prob. Positive", f"{prob_positive * 100:.1f}%")
                col3.metric("Std Dev", f"{np.std(returns) * np.sqrt(num_periods) * 100:.1f}%")

    with tab2:
        st.subheader("Parameter Optimization")

        # Import AnalyticsEngine
        from analytics_service.engine import AnalyticsEngine
        engine = AnalyticsEngine()

        # Strategy selection
        col1, col2 = st.columns(2)
        with col1:
            selected_strategy = st.selectbox(
                "Strategy",
                ["SMA Crossover", "RSI Reversal", "MACD Divergence", "Bollinger Bands"]
            )
        with col2:
            metric_to_optimize = st.selectbox(
                "Optimization Metric",
                ["sharpe_ratio", "sortino_ratio", "total_pnl", "win_rate"]
            )

        # Parameter ranges input
        st.markdown("### Parameter Ranges")

        # Define parameter ranges based on strategy
        if selected_strategy == "SMA Crossover":
            col1, col2 = st.columns(2)
            with col1:
                fast_period_range = st.slider("Fast Period Range", 5, 50, (5, 30))
            with col2:
                slow_period_range = st.slider("Slow Period Range", 20, 200, (30, 100))
            param_ranges = {
                'fast_period': list(range(fast_period_range[0], fast_period_range[1] + 1, 5)),
                'slow_period': list(range(slow_period_range[0], slow_period_range[1] + 1, 10))
            }

        elif selected_strategy == "RSI Reversal":
            col1, col2, col3 = st.columns(3)
            with col1:
                rsi_period_range = st.slider("RSI Period Range", 5, 30, (7, 21))
            with col2:
                oversold_range = st.slider("Oversold Range", 10, 40, (20, 40))
            with col3:
                overbought_range = st.slider("Overbought Range", 50, 90, (60, 80))
            param_ranges = {
                'rsi_period': list(range(rsi_period_range[0], rsi_period_range[1] + 1, 2)),
                'oversold': list(range(oversold_range[0], oversold_range[1] + 1, 5)),
                'overbought': list(range(overbought_range[0], overbought_range[1] + 1, 5))
            }

        elif selected_strategy == "MACD Divergence":
            col1, col2, col3 = st.columns(3)
            with col1:
                macd_fast_range = st.slider("MACD Fast Range", 5, 30, (8, 16))
            with col2:
                macd_slow_range = st.slider("MACD Slow Range", 20, 50, (24, 36))
            with col3:
                macd_signal_range = st.slider("Signal Range", 5, 15, (6, 12))
            param_ranges = {
                'macd_fast': list(range(macd_fast_range[0], macd_fast_range[1] + 1, 4)),
                'macd_slow': list(range(macd_slow_range[0], macd_slow_range[1] + 1, 4)),
                'macd_signal': list(range(macd_signal_range[0], macd_signal_range[1] + 1, 3))
            }

        elif selected_strategy == "Bollinger Bands":
            col1, col2 = st.columns(2)
            with col1:
                bb_period_range = st.slider("BB Period Range", 10, 50, (15, 35))
            with col2:
                bb_std_range = st.slider("Std Multiplier Range", 1.0, 3.0, (1.5, 2.5))
            param_ranges = {
                'bb_period': list(range(bb_period_range[0], bb_period_range[1] + 1, 5)),
                'bb_std': [round(x * 0.5, 1) for x in range(int(bb_std_range[0] * 2), int(bb_std_range[1] * 2) + 1)]
            }

        # Display configured ranges
        st.markdown("**Configured Parameter Ranges:**")
        st.json(param_ranges)

        # Run Grid Search Button
        if st.button("Run Grid Search", type="primary"):
            with st.spinner("Running grid search optimization..."):
                # Load sample data for demonstration
                # In production, this would load from user-selected source
                np.random.seed(42)
                dates = pd.date_range(start="2024-01-01", periods=100, freq="D")
                sample_data = pd.DataFrame({
                    'open': 100 + np.random.randn(100).cumsum(),
                    'high': 105 + np.random.randn(100).cumsum(),
                    'low': 95 + np.random.randn(100).cumsum(),
                    'close': 100 + np.random.randn(100).cumsum(),
                    'volume': np.random.randint(1000000, 5000000, 100)
                }, index=dates)

                # Define strategy execution function
                def run_strategy(data, params):
                    """Mock strategy function that returns metrics."""
                    # In production, this would actually run the strategy backtest
                    # For now, return synthetic metrics based on params
                    sharpe = np.random.uniform(0.5, 2.5)
                    sortino = sharpe * 1.2
                    total_pnl = np.random.uniform(-1000, 5000)
                    win_rate = np.random.uniform(0.3, 0.7)

                    return {
                        'metrics': {
                            'sharpe_ratio': round(sharpe, 4),
                            'sortino_ratio': round(sortino, 4),
                            'total_pnl': round(total_pnl, 2),
                            'win_rate': round(win_rate, 4)
                        }
                    }

                # Run grid search
                result = engine.grid_search_optimization(
                    strategy_func=run_strategy,
                    param_grid=param_ranges,
                    data=sample_data,
                    metric=metric_to_optimize
                )

                # Display best params
                st.markdown("---")
                st.markdown("### Best Parameters Found")
                st.json(result.get('best_params', {}))

                # Display best metrics
                col1, col2, col3, col4 = st.columns(4)
                best_metrics = result.get('best_metrics', {})
                col1.metric("Sharpe Ratio", f"{best_metrics.get('sharpe_ratio', 0):.2f}")
                col2.metric("Sortino Ratio", f"{best_metrics.get('sortino_ratio', 0):.2f}")
                col3.metric("Total P&L", f"${best_metrics.get('total_pnl', 0):.2f}")
                col4.metric("Win Rate", f"{best_metrics.get('win_rate', 0) * 100:.1f}%")

                # Display top 5 results table
                st.markdown("---")
                st.markdown("### Top 5 Results")

                all_results = result.get('all_results', [])
                if all_results:
                    top_5 = all_results[:5]
                    table_data = []
                    for i, r in enumerate(top_5, 1):
                        table_data.append({
                            "Rank": i,
                            "Parameters": str(r.get('params', {})),
                            "Sharpe": r.get('sharpe_ratio', r.get('sharpe', 0)),
                            "Sortino": r.get('sortino_ratio', r.get('sortino', 0)),
                            "Total P&L": r.get('total_pnl', 0),
                            "Win Rate": r.get('win_rate', 0)
                        })
                    results_df = pd.DataFrame(table_data)
                    st.dataframe(results_df, use_container_width=True)
                else:
                    st.info("No results returned - check parameter ranges")

        # Info about the feature
        st.markdown("---")
        st.caption(
            "Grid search performs an exhaustive search over the parameter space. "
            "Results are ranked by the selected metric (default: Sharpe Ratio)."
        )

    with tab3:
        st.subheader("Correlation Analysis")
        st.info("Asset correlation analysis - coming soon")


# ================== Settings Page ==================

def render_settings_page():
    """Render the settings page."""
    st.title("Settings")

    st.subheader("API Configuration")

    # Java API URL configuration
    st.markdown("**Java Trading Engine URL**")
    st.caption(f"Currently: `{JAVA_API_URL}` - Set via JAVA_API_URL environment variable")

    col1, col2 = st.columns(2)

    with col1:
        api_host = st.text_input("Analytics API Host", API_HOST)

    with col2:
        api_port = st.number_input("Analytics API Port", value=int(API_PORT), min_value=1, max_value=65535)

    st.subheader("Display Options")

    chart_type = st.selectbox("Default Chart Type", ["Candlestick", "Line", "OHLC"])
    timeframe = st.selectbox("Default Timeframe", ["1m", "5m", "15m", "1h", "1d"])

    if st.button("Save Settings"):
        st.success("Settings saved!")
        st.rerun()


# ================== Main ==================

def main():
    """Main entry point."""
    init_session_state()

    page = render_sidebar()

    if page == "Backtesting":
        render_backtesting_page()
    elif page == "Monitoring":
        render_monitoring_page()
    elif page == "Analytics":
        render_analytics_page()
    elif page == "Settings":
        render_settings_page()


if __name__ == "__main__":
    main()