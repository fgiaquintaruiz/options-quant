"""Unit tests for AnalyticsEngine (no HTTP)."""

import numpy as np
import pandas as pd
import pytest

from analytics_service.engine import AnalyticsEngine


@pytest.fixture
def engine():
    return AnalyticsEngine()


def test_missing_close_raises(engine):
    df = pd.DataFrame({"open": [1.0]})
    with pytest.raises(ValueError, match="close"):
        engine.calculate_technical_indicators(df, ["SMA"])


def test_params_none_defaults_empty_dict(engine):
    df = pd.DataFrame({"close": [1.0, 2.0, 3.0]})
    out = engine.calculate_technical_indicators(df, ["SMA"], params=None)
    assert "SMA_20" in out.columns


def test_each_indicator_branch(engine):
    df = pd.DataFrame(
        {
            "open": [10.0] * 40,
            "high": [11.0] * 40,
            "low": [9.0] * 40,
            "close": np.linspace(10, 20, 40),
            "volume": [1000] * 40,
        }
    )
    params = {
        "SMA": {"period": 3},
        "EMA": {"period": 3},
        "RSI": {"period": 3},
        "MACD": {"fast": 8, "slow": 17, "signal": 5},
        "BB": {"period": 5, "std_mult": 2.0},
        "ATR": {"period": 5},
        "STOCH": {"period": 5},
    }
    indicators = ["SMA", "EMA", "RSI", "MACD", "BB", "ATR", "STOCH", "UNKNOWN"]
    out = engine.calculate_technical_indicators(df, indicators, params=params)
    assert "SMA_3" in out.columns
    assert "EMA_3" in out.columns
    assert "RSI_3" in out.columns
    assert "MACD_8_17" in out.columns
    assert "BB_upper_5" in out.columns
    assert "ATR_5" in out.columns
    assert "STOCH_K_5" in out.columns


def test_empty_trades_metrics(engine):
    m = engine.calculate_performance_metrics([])
    assert m["total_trades"] == 0
    assert m["profit_factor"] == 0.0


def test_all_winners_profit_factor_none(engine):
    m = engine.calculate_performance_metrics([{"pnl": 10, "return": 0.1}, {"pnl": 5, "return": 0.05}])
    assert m["profit_factor"] is None


def test_sharpe_zero_std(engine):
    m = engine.calculate_performance_metrics(
        [{"pnl": 1, "return": 0.0}, {"pnl": 1, "return": 0.0}]
    )
    assert m["sharpe_ratio"] == 0.0


def test_sharpe_single_trade_early_exit(engine):
    m = engine.calculate_performance_metrics([{"pnl": 1, "return": 0.1}])
    assert m["sharpe_ratio"] == 0.0


def test_sortino_single_trade_early_exit(engine):
    m = engine.calculate_performance_metrics([{"pnl": 1, "return": 0.1}])
    assert m["sortino_ratio"] == 0.0


def test_sortino_mixed_returns_full_path(engine):
    # At least two negative returns so downside std is non-zero (single value → std == 0)
    m = engine.calculate_performance_metrics(
        [
            {"pnl": 1, "return": 0.1},
            {"pnl": -1, "return": -0.05},
            {"pnl": -1, "return": -0.08},
            {"pnl": 1, "return": 0.02},
        ]
    )
    # Main Sortino path (not 0 / inf / early exit); sign depends on mean vs downside
    assert m["sortino_ratio"] not in (0.0, float("inf"))
    assert not np.isnan(m["sortino_ratio"])


def test_sortino_downside_zero_std(engine):
    m = engine.calculate_performance_metrics(
        [{"pnl": -1, "return": -0.1}, {"pnl": -1, "return": -0.1}]
    )
    assert m["sortino_ratio"] == 0.0


def test_sortino_no_downside_inf(engine):
    # All positive excess returns -> Sortino path returns inf; rounded stays inf in metrics
    m = engine.calculate_performance_metrics(
        [{"pnl": 1, "return": 0.1}, {"pnl": 1, "return": 0.2}]
    )
    assert m["sortino_ratio"] == float("inf")


def test_monte_carlo_empty_returns(engine):
    r = engine.run_monte_carlo_simulation([], num_simulations=100, num_periods=10)
    assert r["paths"] == []
    assert r["summary"]["mean_return"] == 0.0


def test_monte_carlo_happy_path(engine):
    rets = [0.01, -0.02, 0.015] * 20
    r = engine.run_monte_carlo_simulation(rets, num_simulations=50, num_periods=30)
    assert "summary" in r
    assert len(r["paths"]) == 50


def test_grid_search_empty_grid(engine):
    out = engine.grid_search_optimization(lambda d, p: {}, {}, pd.DataFrame({"close": [1.0]}))
    assert out["best_params"] == {}
    assert out["all_results"] == []


def test_grid_search_success_and_failure(engine):
    df = pd.DataFrame({"close": [1.0, 2.0]})

    def strat(data, params):
        if params.get("x") == 2:
            raise RuntimeError("bad")
        return {"metrics": {"sharpe_ratio": float(params.get("x", 0))}}

    out = engine.grid_search_optimization(
        strat, {"x": [1, 2]}, df, metric="sharpe_ratio"
    )
    assert len(out["all_results"]) == 2
    assert out["best_params"]["x"] == 1


def test_grid_search_non_dict_strategy_result(engine):
    df = pd.DataFrame({"close": [1.0, 2.0]})

    def strat(data, params):
        return 42  # not a dict

    out = engine.grid_search_optimization(strat, {"x": [1]}, df, metric="sharpe_ratio")
    assert out["best_params"]["x"] == 1


def test_max_drawdown_empty_pnls_private(engine):
    assert engine._calculate_max_drawdown([]) == 0.0
