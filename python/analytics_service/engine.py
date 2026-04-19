"""
Analytics Engine - Technical indicators, performance metrics, and Monte Carlo simulation.

This module provides the core analytics functionality for the Python analytics service.
It handles technical indicator calculation, performance metrics, and Monte Carlo simulations.
"""

import numpy as np
import pandas as pd
from typing import List, Dict, Optional, Union
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class AnalyticsEngine:
    """
    Analytics Engine for calculating technical indicators,
    performance metrics, and running Monte Carlo simulations.
    """

    def __init__(self):
        """Initialize the Analytics Engine."""
        self.available_indicators = ['SMA', 'EMA', 'RSI', 'MACD', 'BB', 'ATR', 'STOCH']
        logger.info("AnalyticsEngine initialized")

    def calculate_technical_indicators(
        self,
        df: pd.DataFrame,
        indicators: List[str],
        params: Optional[Dict[str, Dict]] = None
    ) -> pd.DataFrame:
        """
        Calculate technical indicators on market data.

        Args:
            df: DataFrame with 'open', 'high', 'low', 'close', 'volume' columns
            indicators: List of indicator names (SMA, EMA, RSI, MACD, BB, ATR, STOCH)
            params: Optional parameters for each indicator

        Returns:
            DataFrame with added indicator columns

        Raises:
            ValueError: If required columns are missing or indicators are invalid
        """
        required_cols = ['close']
        for col in required_cols:
            if col not in df.columns:
                raise ValueError(f"Missing required column: {col}")

        if params is None:
            params = {}

        result = df.copy()

        for indicator in indicators:
            indicator_upper = indicator.upper()
            if indicator_upper == 'SMA':
                period = params.get('SMA', {}).get('period', 20)
                result[f'SMA_{period}'] = self._calculate_sma(df['close'], period)
            elif indicator_upper == 'EMA':
                period = params.get('EMA', {}).get('period', 20)
                result[f'EMA_{period}'] = self._calculate_ema(df['close'], period)
            elif indicator_upper == 'RSI':
                period = params.get('RSI', {}).get('period', 14)
                result[f'RSI_{period}'] = self._calculate_rsi(df['close'], period)
            elif indicator_upper == 'MACD':
                fast = params.get('MACD', {}).get('fast', 12)
                slow = params.get('MACD', {}).get('slow', 26)
                signal = params.get('MACD', {}).get('signal', 9)
                macd_line, macd_signal, macd_hist = self._calculate_macd(df['close'], fast, slow, signal)
                result[f'MACD_{fast}_{slow}'] = macd_line
                result[f'MACD_signal_{signal}'] = macd_signal
                result[f'MACD_hist_{fast}_{slow}'] = macd_hist
            elif indicator_upper == 'BB':
                period = params.get('BB', {}).get('period', 20)
                std_mult = params.get('BB', {}).get('std_mult', 2.0)
                bb_upper, bb_middle, bb_lower = self._calculate_bollinger_bands(df['close'], period, std_mult)
                result[f'BB_upper_{period}'] = bb_upper
                result[f'BB_middle_{period}'] = bb_middle
                result[f'BB_lower_{period}'] = bb_lower
            elif indicator_upper == 'ATR':
                period = params.get('ATR', {}).get('period', 14)
                result[f'ATR_{period}'] = self._calculate_atr(df, period)
            elif indicator_upper == 'STOCH':
                period = params.get('STOCH', {}).get('period', 14)
                k, d = self._calculate_stochastic(df, period)
                result[f'STOCH_K_{period}'] = k
                result[f'STOCH_D_{period}'] = d
            else:
                logger.warning(f"Unknown indicator: {indicator}")

        logger.info(f"Calculated indicators: {indicators}")
        return result

    def _calculate_sma(self, series: pd.Series, period: int) -> pd.Series:
        """Calculate Simple Moving Average."""
        return series.rolling(window=period).mean()

    def _calculate_ema(self, series: pd.Series, period: int) -> pd.Series:
        """Calculate Exponential Moving Average."""
        return series.ewm(span=period, adjust=False).mean()

    def _calculate_rsi(self, series: pd.Series, period: int = 14) -> pd.Series:
        """Calculate Relative Strength Index."""
        delta = series.diff()
        gain = (delta.where(delta > 0, 0)).rolling(window=period).mean()
        loss = (-delta.where(delta < 0, 0)).rolling(window=period).mean()
        rs = gain / loss
        rsi = 100 - (100 / (1 + rs))
        return rsi

    def _calculate_macd(
        self,
        series: pd.Series,
        fast: int = 12,
        slow: int = 26,
        signal: int = 9
    ) -> tuple:
        """Calculate MACD (Moving Average Convergence Divergence)."""
        ema_fast = series.ewm(span=fast, adjust=False).mean()
        ema_slow = series.ewm(span=slow, adjust=False).mean()
        macd_line = ema_fast - ema_slow
        macd_signal = macd_line.ewm(span=signal, adjust=False).mean()
        macd_hist = macd_line - macd_signal
        return macd_line, macd_signal, macd_hist

    def _calculate_bollinger_bands(
        self,
        series: pd.Series,
        period: int = 20,
        std_mult: float = 2.0
    ) -> tuple:
        """Calculate Bollinger Bands."""
        middle = series.rolling(window=period).mean()
        std = series.rolling(window=period).std()
        upper = middle + (std * std_mult)
        lower = middle - (std * std_mult)
        return upper, middle, lower

    def _calculate_atr(self, df: pd.DataFrame, period: int = 14) -> pd.Series:
        """Calculate Average True Range."""
        high_low = df['high'] - df['low']
        high_close = np.abs(df['high'] - df['close'].shift())
        low_close = np.abs(df['low'] - df['close'].shift())
        true_range = pd.concat([high_low, high_close, low_close], axis=1).max(axis=1)
        return true_range.rolling(window=period).mean()

    def _calculate_stochastic(
        self,
        df: pd.DataFrame,
        period: int = 14
    ) -> tuple:
        """Calculate Stochastic Oscillator (%K and %D)."""
        low_min = df['low'].rolling(window=period).min()
        high_max = df['high'].rolling(window=period).max()
        k = 100 * (df['close'] - low_min) / (high_max - low_min)
        d = k.rolling(window=3).mean()
        return k, d

    def calculate_performance_metrics(self, trades: List[Dict]) -> Dict[str, float]:
        """
        Calculate performance metrics from trade history.

        Args:
            trades: List of trade dictionaries, each with:
                - 'pnl': profit/loss
                - 'return': return percentage (optional)
                - 'duration': holding period in days (optional)

        Returns:
            Dictionary with performance metrics:
                - total_trades
                - winning_trades
                - losing_trades
                - win_rate
                - total_pnl
                - avg_pnl
                - sharpe_ratio
                - sortino_ratio
                - max_drawdown
                - profit_factor
        """
        if not trades:
            return {
                'total_trades': 0,
                'winning_trades': 0,
                'losing_trades': 0,
                'win_rate': 0.0,
                'total_pnl': 0.0,
                'avg_pnl': 0.0,
                'sharpe_ratio': 0.0,
                'sortino_ratio': 0.0,
                'max_drawdown': 0.0,
                'profit_factor': 0.0
            }

        pnls = [t.get('pnl', 0) for t in trades]
        returns = [t.get('return', 0) for t in trades]

        winning_trades = [p for p in pnls if p > 0]
        losing_trades = [p for p in pnls if p <= 0]

        total_pnl = sum(pnls)
        avg_pnl = total_pnl / len(pnls) if pnls else 0
        win_rate = len(winning_trades) / len(pnls) if pnls else 0

        # Sharpe Ratio (assuming risk-free rate of 0 for simplicity)
        sharpe_ratio = self._calculate_sharpe_ratio(returns)

        # Sortino Ratio
        sortino_ratio = self._calculate_sortino_ratio(returns)

        # Max Drawdown
        max_drawdown = self._calculate_max_drawdown(pnls)

        # Profit Factor
        profit_factor = abs(sum(winning_trades) / sum(losing_trades)) if losing_trades else float('inf')

        metrics = {
            'total_trades': len(trades),
            'winning_trades': len(winning_trades),
            'losing_trades': len(losing_trades),
            'win_rate': round(win_rate, 4),
            'total_pnl': round(total_pnl, 2),
            'avg_pnl': round(avg_pnl, 2),
            'sharpe_ratio': round(sharpe_ratio, 4),
            'sortino_ratio': round(sortino_ratio, 4),
            'max_drawdown': round(max_drawdown, 2),
            'profit_factor': round(profit_factor, 4) if profit_factor != float('inf') else float('inf')
        }

        logger.info(f"Calculated performance metrics for {len(trades)} trades")
        return metrics

    def _calculate_sharpe_ratio(self, returns: List[float], risk_free_rate: float = 0.0) -> float:
        """Calculate Sharpe ratio."""
        if not returns or len(returns) < 2:
            return 0.0

        returns_array = np.array(returns)
        excess_returns = returns_array - risk_free_rate
        std_returns = np.std(returns_array)

        if std_returns == 0:
            return 0.0

        # Annualized (assuming 252 trading days)
        return np.mean(excess_returns) / std_returns * np.sqrt(252)

    def _calculate_sortino_ratio(self, returns: List[float], risk_free_rate: float = 0.0) -> float:
        """Calculate Sortino ratio (downside deviation only)."""
        if not returns or len(returns) < 2:
            return 0.0

        returns_array = np.array(returns)
        excess_returns = returns_array - risk_free_rate
        downside_returns = excess_returns[excess_returns < 0]

        if len(downside_returns) == 0:
            return float('inf')

        downside_std = np.std(downside_returns)

        if downside_std == 0:
            return 0.0

        # Annualized
        return np.mean(excess_returns) / downside_std * np.sqrt(252)

    def _calculate_max_drawdown(self, pnls: List[float]) -> float:
        """Calculate maximum drawdown from P&L series."""
        if not pnls:
            return 0.0

        equity = np.cumsum(pnls)
        running_max = np.maximum.accumulate(equity)
        drawdown = running_max - equity
        return np.max(drawdown)

    def run_monte_carlo_simulation(
        self,
        returns: List[float],
        num_simulations: int = 1000,
        num_periods: int = 252
    ) -> Dict[str, Union[List[float], Dict]]:
        """
        Run Monte Carlo simulation for strategy equity curves.

        Args:
            returns: Historical returns (percentages or raw values)
            num_simulations: Number of simulation paths to generate
            num_periods: Number of time periods to simulate

        Returns:
            Dictionary containing:
            - paths: List of simulated equity curve paths
            - summary: Statistical summary of outcomes
        """
        if not returns:
            return {
                'paths': [],
                'summary': {
                    'mean_return': 0.0,
                    'median_return': 0.0,
                    'min_return': 0.0,
                    'max_return': 0.0,
                    'std_return': 0.0,
                    'prob_positive': 0.0,
                    'prob_10_loss': 0.0,
                    'prob_10_gain': 0.0
                }
            }

        # Calculate parameters from historical returns
        mean_return = np.mean(returns)
        std_return = np.std(returns)

        # Generate random returns
        np.random.seed(42)  # For reproducibility
        random_returns = np.random.normal(
            mean_return,
            std_return,
            (num_simulations, num_periods)
        )

        # Calculate equity paths (starting at 1.0)
        initial_balance = 1.0
        paths = initial_balance * np.cumprod(1 + random_returns, axis=1)

        # Calculate final returns for each path
        final_returns = paths[:, -1] - initial_balance

        summary = {
            'mean_return': round(float(np.mean(final_returns)), 4),
            'median_return': round(float(np.median(final_returns)), 4),
            'min_return': round(float(np.min(final_returns)), 4),
            'max_return': round(float(np.max(final_returns)), 4),
            'std_return': round(float(np.std(final_returns)), 4),
            'prob_positive': round(float(np.sum(final_returns > 0) / num_simulations), 4),
            'prob_10_loss': round(float(np.sum(final_returns < -0.10) / num_simulations), 4),
            'prob_10_gain': round(float(np.sum(final_returns > 0.10) / num_simulations), 4)
        }

        logger.info(f"Completed Monte Carlo simulation: {num_simulations} paths over {num_periods} periods")
        return {
            'paths': paths.tolist(),
            'summary': summary
        }

    def grid_search_optimization(
        self,
        strategy_func,
        param_grid: Dict[str, List],
        data: pd.DataFrame,
        metric: str = 'sharpe_ratio'
    ) -> Dict:
        """
        Perform grid search for strategy parameter optimization.

        Args:
            strategy_func: Function that takes (data, params) and returns metrics dict
            param_grid: Dictionary of parameter names to list of values
            data: Historical market data DataFrame
            metric: Metric to optimize (default: sharpe_ratio)

        Returns:
            Dictionary with:
                - best_params: Best parameter combination
                - best_metrics: Metrics achieved with best params
                - all_results: List of all results sorted by metric descending
        """
        from itertools import product

        if not param_grid:
            logger.warning("Empty param_grid provided to grid_search_optimization")
            return {
                'best_params': {},
                'best_metrics': {},
                'all_results': []
            }

        # Build Cartesian product of parameter combinations
        param_names = list(param_grid.keys())
        param_values = list(param_grid.values())
        combinations = list(product(*param_values))

        logger.info(f"Running grid search over {len(combinations)} parameter combinations")

        all_results = []

        # Run strategy with each parameter combination
        for combo in combinations:
            params = dict(zip(param_names, combo))

            try:
                # Run strategy with these params
                result = strategy_func(data, params)

                # Extract metric value (handle nested metrics dict)
                metrics = result.get('metrics', {}) if isinstance(result, dict) else result
                if isinstance(metrics, dict):
                    metric_value = metrics.get(metric, 0.0)
                else:
                    metric_value = 0.0

                all_results.append({
                    'params': params,
                    'metrics': metrics if isinstance(metrics, dict) else {},
                    metric: metric_value
                })

            except Exception as e:
                logger.warning(f"Strategy failed with params {params}: {e}")
                all_results.append({
                    'params': params,
                    'metrics': {},
                    metric: 0.0,
                    'error': str(e)
                })

        # Sort by metric descending
        all_results.sort(key=lambda x: x.get(metric, 0.0), reverse=True)

        # Extract best result
        best_result = all_results[0] if all_results else {'params': {}, 'metrics': {}, metric: 0.0}

        return {
            'best_params': best_result.get('params', {}),
            'best_metrics': best_result.get('metrics', {}),
            'all_results': all_results
        }