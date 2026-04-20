"""
Unit tests for AnalyticsEngine - grid_search_optimization.

Tests the grid search optimizer with deterministic mock strategies.
"""

import pandas as pd
import numpy as np
from analytics_service.engine import AnalyticsEngine


def mock_sma_strategy(data: pd.DataFrame, params: dict) -> dict:
    """
    Mock SMA strategy that returns known metrics based on params.
    Higher period = higher Sharpe (deterministic test case).
    """
    period = params.get('period', 20)

    # Deterministic metrics based on period
    # Higher period -> higher sharpe_ratio (for test)
    # period 20: sharpe 1.5
    # period 50: sharpe 2.0
    sharpe = 1.5 + (period - 20) * 0.02
    sortino = sharpe * 1.1
    total_pnl = period * 10.0
    win_rate = 0.5 + (period % 100) * 0.001

    return {
        'metrics': {
            'sharpe_ratio': round(sharpe, 4),
            'sortino_ratio': round(sortino, 4),
            'total_pnl': round(total_pnl, 2),
            'win_rate': round(win_rate, 4),
            'total_trades': period,
        }
    }


def mock_rsi_strategy(data: pd.DataFrame, params: dict) -> dict:
    """
    Mock RSI strategy - higher RSI threshold = higher Sharpe.
    """
    threshold = params.get('threshold', 30)

    # threshold 30: sharpe 1.0
    # threshold 70: sharpe 2.5
    sharpe = 1.0 + (threshold - 30) * 0.0375

    return {
        'metrics': {
            'sharpe_ratio': round(sharpe, 4),
            'sortino_ratio': round(sharpe * 0.9, 4),
            'total_pnl': threshold * 5.0,
            'win_rate': 0.55,
        }
    }


def mock_strategy_with_error(data: pd.DataFrame, params: dict) -> dict:
    """Mock strategy that raises error for specific params."""
    if params.get('error_case'):
        raise ValueError("Intentional error for testing")
    return {'metrics': {'sharpe_ratio': 1.0}}


def create_test_data() -> pd.DataFrame:
    """Create deterministic test data for strategies."""
    np.random.seed(42)
    dates = pd.date_range('2024-01-01', periods=100, freq='D')
    prices = 100 + np.cumsum(np.random.randn(100) * 0.5)

    return pd.DataFrame({
        'date': dates,
        'open': prices - 0.5,
        'high': prices + 1.0,
        'low': prices - 1.0,
        'close': prices,
        'volume': np.random.randint(1000000, 5000000, 100)
    })


class TestGridSearchOptimization:
    """Tests for grid_search_optimization method."""

    def test_grid_search_returns_best_params_highest_sharpe(self):
        """Top result should have highest sharpe_ratio."""
        engine = AnalyticsEngine()
        data = create_test_data()

        param_grid = {
            'period': [20, 30, 40, 50]
        }

        result = engine.grid_search_optimization(
            strategy_func=mock_sma_strategy,
            param_grid=param_grid,
            data=data,
            metric='sharpe_ratio'
        )

        # Verify structure
        assert 'best_params' in result
        assert 'best_metrics' in result
        assert 'all_results' in result

        # Verify best result has highest sharpe
        all_results = result['all_results']
        assert len(all_results) == 4, f"Expected 4 results, got {len(all_results)}"

        # Check ordering: best first
        sharpe_values = [r['sharpe_ratio'] for r in all_results]
        assert sharpe_values == sorted(sharpe_values, reverse=True), \
            f"Results not sorted descending: {sharpe_values}"

        # Best should be period=50 (highest sharpe in mock)
        assert result['best_params']['period'] == 50
        assert result['best_metrics']['sharpe_ratio'] == 2.1  # 1.5 + (50-20)*0.02

    def test_grid_search_with_multiple_params(self):
        """Grid search works with multiple parameter dimensions."""
        engine = AnalyticsEngine()
        data = create_test_data()

        param_grid = {
            'threshold': [30, 40, 50, 60, 70]
        }

        result = engine.grid_search_optimization(
            strategy_func=mock_rsi_strategy,
            param_grid=param_grid,
            data=data,
            metric='sharpe_ratio'
        )

        assert len(result['all_results']) == 5
        # threshold=70 should be best (highest in mock)
        assert result['best_params']['threshold'] == 70

    def test_grid_search_handles_empty_param_grid(self):
        """Empty param_grid returns empty results."""
        engine = AnalyticsEngine()
        data = create_test_data()

        result = engine.grid_search_optimization(
            strategy_func=mock_sma_strategy,
            param_grid={},
            data=data
        )

        assert result['best_params'] == {}
        assert result['best_metrics'] == {}
        assert result['all_results'] == []

    def test_grid_search_handles_strategy_errors(self):
        """Strategy errors are caught and logged, not propagated."""
        engine = AnalyticsEngine()
        data = create_test_data()

        param_grid = {
            'error_case': [False, True, False]
        }

        result = engine.grid_search_optimization(
            strategy_func=mock_strategy_with_error,
            param_grid=param_grid,
            data=data
        )

        # Should have 3 results: 2 success + 1 error
        assert len(result['all_results']) == 3

        # Error case should have error field
        error_results = [r for r in result['all_results'] if 'error' in r]
        assert len(error_results) == 1

    def test_grid_search_default_metric_is_sharpe_ratio(self):
        """Default metric is sharpe_ratio."""
        engine = AnalyticsEngine()
        data = create_test_data()

        param_grid = {'period': [20, 30]}

        # Call without metric param
        result = engine.grid_search_optimization(
            strategy_func=mock_sma_strategy,
            param_grid=param_grid,
            data=data
        )

        # Should use sharpe_ratio as default
        assert 'sharpe_ratio' in result['best_metrics']
        assert result['best_params']['period'] == 30  # Higher period = higher sharpe


def test_integration_with_real_engine():
    """Integration test with real AnalyticsEngine methods."""
    engine = AnalyticsEngine()
    data = create_test_data()

    # Add indicators
    data_with_indicators = engine.calculate_technical_indicators(data, ['SMA', 'RSI'])

    # Verify indicators were calculated
    assert 'SMA_20' in data_with_indicators.columns
    assert 'RSI_14' in data_with_indicators.columns

    # Test performance metrics calculation
    trades = [
        {'pnl': 100, 'return': 0.1, 'duration': 1},
        {'pnl': -50, 'return': -0.05, 'duration': 2},
        {'pnl': 200, 'return': 0.2, 'duration': 1},
    ]

    metrics = engine.calculate_performance_metrics(trades)

    assert metrics['total_trades'] == 3
    assert metrics['winning_trades'] == 2
    assert metrics['losing_trades'] == 1
    assert metrics['win_rate'] > 0


if __name__ == '__main__':
    import pytest
    pytest.main([__file__, '-v'])