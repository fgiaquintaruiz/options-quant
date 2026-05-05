"""
HTTP tests for FastAPI app (no running Java process — java_client is mocked).
"""

from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def client(monkeypatch):
    from analytics_service import main as mod

    mock_j = MagicMock()
    mock_j.connected = True
    mock_j.client = MagicMock()
    mock_j.get_candles.return_value = [{"close": 100.0, "open": 99}]
    mock_j.get_account_status.return_value = {"cash": 1000}
    monkeypatch.setattr(mod, "java_client", mock_j)
    return TestClient(mod.app), mock_j


def test_root():
    from analytics_service.main import app

    r = TestClient(app).get("/")
    assert r.status_code == 200
    assert r.json().get("service") == "analytics"


def test_health(client):
    c, _ = client
    r = c.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "healthy"
    assert body["java_grpc_connected"] is True


def test_indicators_ok(client):
    c, _ = client
    payload = {
        "data": [{"close": 10.0}, {"close": 11.0}, {"close": 12.0}],
        "indicators": ["SMA"],
        "params": {"SMA": {"period": 2}},
    }
    r = c.post("/api/v1/indicators", json=payload)
    assert r.status_code == 200
    assert r.json()["status"] == "success"
    assert "SMA_2" in str(r.json()["data"])


def test_indicators_bad_request(client):
    c, _ = client
    payload = {"data": [{"open": 1}], "indicators": ["SMA"]}
    r = c.post("/api/v1/indicators", json=payload)
    assert r.status_code == 400


def test_metrics_ok(client):
    c, _ = client
    trades = [{"pnl": 10, "return": 0.1}, {"pnl": -5, "return": -0.05}]
    r = c.post("/api/v1/metrics", json={"trades": trades})
    assert r.status_code == 200
    assert r.json()["metrics"]["total_trades"] == 2


def test_monte_carlo_ok(client):
    c, _ = client
    r = c.post(
        "/api/v1/monte-carlo",
        json={"returns": [0.01, -0.02, 0.015] * 30, "num_simulations": 100, "num_periods": 50},
    )
    assert r.status_code == 200
    assert "summary" in r.json()


def test_market_data_and_account(client):
    c, mock_j = client
    r = c.get("/api/v1/market-data", params={"ticker": "SPY", "timeframe": "5", "limit": 10})
    assert r.status_code == 200
    mock_j.get_candles.assert_called()

    r2 = c.get("/api/v1/account-status")
    assert r2.status_code == 200
    mock_j.get_account_status.assert_called()


def test_market_data_java_disconnected(monkeypatch):
    from analytics_service import main as mod

    mock_j = MagicMock()
    mock_j.connected = False
    monkeypatch.setattr(mod, "java_client", mock_j)
    c = TestClient(mod.app)
    r = c.get("/api/v1/market-data", params={"ticker": "SPY"})
    assert r.status_code == 503


@patch("analytics_service.main.httpx.Client")
def test_java_api_client_connect_ok(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    inst.get.return_value.status_code = 200
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connect()
    assert jc.connected is True
    jc.close()
    assert jc.connected is False


def test_java_api_client_connect_failure():
    from analytics_service.main import JavaApiClient

    jc = JavaApiClient(host="127.0.0.1", port=1)
    jc.connect()
    assert jc.connected is False


def test_metrics_internal_error(client, monkeypatch):
    from analytics_service import main as mod

    def boom(_trades):
        raise RuntimeError("boom")

    monkeypatch.setattr(mod.analytics_engine, "calculate_performance_metrics", boom)
    c, _ = client
    r = c.post("/api/v1/metrics", json={"trades": [{"pnl": 1}]})
    assert r.status_code == 500


def test_account_status_java_none(client, monkeypatch):
    from analytics_service import main as mod

    mock_j = MagicMock()
    mock_j.connected = True
    mock_j.get_account_status.return_value = None
    monkeypatch.setattr(mod, "java_client", mock_j)
    c = TestClient(mod.app)
    r = c.get("/api/v1/account-status")
    assert r.status_code == 502


def test_market_data_candles_none_when_connected(client):
    c, mock_j = client
    mock_j.get_candles.return_value = None
    r = c.get("/api/v1/market-data", params={"ticker": "SPY"})
    assert r.status_code == 502


def test_indicators_internal_error(client, monkeypatch):
    from analytics_service import main as mod

    def boom(*args, **kwargs):
        raise RuntimeError("calc failed")

    monkeypatch.setattr(mod.analytics_engine, "calculate_technical_indicators", boom)
    c, _ = client
    r = c.post(
        "/api/v1/indicators",
        json={"data": [{"close": 1.0}], "indicators": ["SMA"]},
    )
    assert r.status_code == 500


def test_monte_carlo_internal_error(client, monkeypatch):
    from analytics_service import main as mod

    def boom(**kwargs):
        raise RuntimeError("mc failed")

    monkeypatch.setattr(mod.analytics_engine, "run_monte_carlo_simulation", boom)
    c, _ = client
    r = c.post(
        "/api/v1/monte-carlo",
        json={"returns": [0.01, -0.02], "num_simulations": 100, "num_periods": 20},
    )
    assert r.status_code == 500


@patch("analytics_service.main.httpx.Client")
def test_java_api_client_health_non_200(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    inst.get.return_value.status_code = 503
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connect()
    assert jc.connected is False


@patch("analytics_service.main.httpx.Client")
def test_java_get_account_status_exception(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    inst.get.side_effect = RuntimeError("network")
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connected = True
    jc.client = inst
    assert jc.get_account_status() is None


@patch("analytics_service.main.httpx.Client")
def test_java_get_candles_exception(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    inst.get.side_effect = RuntimeError("network")
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connected = True
    jc.client = inst
    assert jc.get_candles("SPY") is None


def test_lifespan_runs_with_test_client():
    from analytics_service.main import app

    with TestClient(app) as c:
        r = c.get("/health")
        assert r.status_code == 200


def test_account_status_disconnected(monkeypatch):
    from analytics_service import main as mod

    mock_j = MagicMock()
    mock_j.connected = False
    monkeypatch.setattr(mod, "java_client", mock_j)
    c = TestClient(mod.app)
    r = c.get("/api/v1/account-status")
    assert r.status_code == 503


@patch("analytics_service.main.httpx.Client")
def test_java_get_account_status_non_200(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    inst.get.return_value.status_code = 404
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connected = True
    jc.client = inst
    assert jc.get_account_status() is None


@patch("analytics_service.main.httpx.Client")
def test_java_get_candles_200(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    resp = MagicMock()
    resp.status_code = 200
    resp.json.return_value = [{"close": 100.0}]
    inst.get.return_value = resp
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connected = True
    jc.client = inst
    assert jc.get_candles("SPY") == [{"close": 100.0}]


def test_java_close_closes_http_client():
    from analytics_service.main import JavaApiClient

    jc = JavaApiClient()
    mock_http = MagicMock()
    jc.client = mock_http
    jc.connected = True
    jc.close()
    mock_http.close.assert_called_once()
    assert jc.connected is False


def test_java_close_noop_when_no_http_client():
    from analytics_service.main import JavaApiClient

    jc = JavaApiClient()
    jc.client = None
    jc.connected = False
    jc.close()


def test_java_get_account_status_early_exit_disconnected():
    from analytics_service.main import JavaApiClient

    jc = JavaApiClient()
    jc.connected = False
    jc.client = MagicMock()
    assert jc.get_account_status() is None


def test_java_get_candles_early_exit_disconnected():
    from analytics_service.main import JavaApiClient

    jc = JavaApiClient()
    jc.connected = False
    jc.client = MagicMock()
    assert jc.get_candles("SPY") is None


@patch("analytics_service.main.httpx.Client")
def test_java_get_account_status_200(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    resp = MagicMock()
    resp.status_code = 200
    resp.json.return_value = {"cash": 1000}
    inst.get.return_value = resp
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connected = True
    jc.client = inst
    assert jc.get_account_status() == {"cash": 1000}


@patch("analytics_service.main.httpx.Client")
def test_java_get_candles_non_200(mock_client_cls):
    from analytics_service.main import JavaApiClient

    inst = MagicMock()
    resp = MagicMock()
    resp.status_code = 404
    inst.get.return_value = resp
    mock_client_cls.return_value = inst
    jc = JavaApiClient(host="localhost", port=9090)
    jc.connected = True
    jc.client = inst
    assert jc.get_candles("SPY") is None


# ================== Historical Candles tests ==================

def test_historical_happy_path(client):
    import pandas as pd
    from unittest.mock import patch
    c, _ = client
    idx = pd.DatetimeIndex(["2022-01-03", "2022-01-04", "2022-01-05"])
    mock_df = pd.DataFrame({
        "Open": [100.0, 101.0, 102.0],
        "High": [105.0, 106.0, 107.0],
        "Low": [99.0, 100.0, 101.0],
        "Close": [104.0, 105.0, 106.0],
        "Volume": [1000, 1100, 1200],
    }, index=idx)
    with patch("yfinance.download", return_value=mock_df):
        response = c.get("/api/v1/historical/AAPL?from=2022-01-03&to=2022-01-06&interval=1d")
    assert response.status_code == 200
    data = response.json()
    assert len(data) == 3
    assert "ts_epoch" in data[0]
    assert data[0]["close"] == 104.0


def test_historical_empty(client):
    import pandas as pd
    from unittest.mock import patch
    c, _ = client
    with patch("yfinance.download", return_value=pd.DataFrame()):
        response = c.get("/api/v1/historical/AAPL?from=2022-01-03&to=2022-01-06&interval=1d")
    assert response.status_code == 200
    assert response.json() == []


def test_historical_unsupported_interval(client):
    c, _ = client
    response = c.get("/api/v1/historical/AAPL?from=2022-01-03&to=2022-01-06&interval=5m")
    assert response.status_code == 400


def test_historical_yfinance_exception(client):
    from unittest.mock import patch
    c, _ = client
    with patch("yfinance.download", side_effect=Exception("network error")):
        response = c.get("/api/v1/historical/AAPL?from=2022-01-03&to=2022-01-06&interval=1d")
    assert response.status_code == 502


def test_historical_vix_ticker(client):
    import pandas as pd
    from unittest.mock import patch
    c, _ = client
    idx = pd.DatetimeIndex(["2022-01-03"])
    mock_df = pd.DataFrame({
        "Open": [18.0], "High": [19.0], "Low": [17.0], "Close": [18.5], "Volume": [0],
    }, index=idx)
    with patch("yfinance.download", return_value=mock_df):
        response = c.get("/api/v1/historical/%5EVIX?from=2022-01-03&to=2022-01-04&interval=1d")
    assert response.status_code == 200
    assert len(response.json()) == 1
