"""
Python Analytics Service - FastAPI client for analytics.

This service provides REST endpoints for:
- Technical indicator calculation
- Performance metrics
- Monte Carlo simulation
- Backtesting

Communication with Java trading engine via REST HTTP.
"""

import json
import os
import logging
from typing import List, Dict, Optional
from contextlib import asynccontextmanager

import httpx
import fastapi
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field
import pandas as pd
import numpy as np

from .engine import AnalyticsEngine

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


# ================== Configuration ==================

JAVA_API_HOST = os.getenv('JAVA_API_HOST', 'localhost')
# Default 9090 = local Spring Boot (application.yml). Docker-compose sets JAVA_API_PORT=8080.
JAVA_API_PORT = os.getenv('JAVA_API_PORT', '9090')
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = os.getenv('REDIS_PORT', '6379')

# ================== Pydantic Models ==================

class IndicatorRequest(BaseModel):
    """Request model for technical indicator calculation."""
    data: List[Dict[str, float]] = Field(..., description="List of OHLCV data points")
    indicators: List[str] = Field(..., description="Indicators to calculate (SMA, EMA, RSI, MACD, BB, ATR, STOCH)")
    params: Optional[Dict[str, Dict]] = Field(default=None, description="Parameters for each indicator")


class PerformanceMetricsRequest(BaseModel):
    """Request model for performance metrics calculation."""
    trades: List[Dict] = Field(..., description="List of trades with P&L data")
    include_confidence_intervals: bool = Field(default=False, description="Calculate confidence intervals")


class MonteCarloRequest(BaseModel):
    """Request model for Monte Carlo simulation."""
    returns: List[float] = Field(..., description="Historical returns")
    num_simulations: int = Field(default=1000, ge=100, le=10000, description="Number of simulations")
    num_periods: int = Field(default=252, ge=10, le=2520, description="Number of periods to simulate")


class MarketDataRequest(BaseModel):
    """Request model for market data from Java gRPC service."""
    symbols: Optional[List[str]] = Field(default=None, description="Symbols to subscribe to")
    timeframe: int = Field(default=5, description="Timeframe in minutes")
    start_time: Optional[int] = Field(default=None, description="Start timestamp")
    end_time: Optional[int] = Field(default=None, description="End timestamp")


class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    service: str
    version: str
    java_grpc_connected: bool


# ================== FastAPI App ==================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifespan context manager for startup/shutdown."""
    logger.info("Starting Analytics Service...")
    logger.info(f"Java REST API: {JAVA_API_HOST}:{JAVA_API_PORT}")
    logger.info(f"Redis: {REDIS_HOST}:{REDIS_PORT}")
    logger.info("Analytics Service started")
    yield
    logger.info("Shutting down Analytics Service...")


app = FastAPI(
    title="Options Quant Analytics Service",
    description="Python analytics service for technical indicators, performance metrics, and Monte Carlo simulation",
    version="1.0.0",
    lifespan=lifespan
)


# ================== REST Client ==================

class JavaApiClient:
    """REST client for connecting to Java trading engine."""

    def __init__(self, host: str = JAVA_API_HOST, port: int = int(JAVA_API_PORT)):
        self.host = host
        self.port = port
        self.base_url = f"http://{host}:{port}"
        self.client = None
        self.connected = False

    def connect(self):
        """Connect to Java REST API."""
        try:
            self.client = httpx.Client(base_url=self.base_url, timeout=10.0)
            # Test connection with health check
            response = self.client.get("/actuator/health")
            self.connected = response.status_code == 200
            if self.connected:
                logger.info(f"Connected to Java REST API at {self.base_url}")
            else:
                logger.warning(f"Java REST health check returned {response.status_code}")
        except Exception as e:
            logger.warning(f"Failed to connect to Java REST API: {e}")
            self.connected = False

    def close(self):
        """Close the REST client."""
        if self.client:
            self.client.close()
            self.connected = False

    def get_account_status(self) -> Optional[Dict]:
        """Get account status from Java trading engine."""
        if not self.connected or not self.client:
            return None
        try:
            response = self.client.get("/api/trading/account-status")
            if response.status_code == 200:
                return response.json()
        except Exception as e:
            logger.warning(f"Failed to get account status: {e}")
        return None

    def get_candles(self, ticker: str, timeframe: str = "5", limit: int = 100) -> Optional[List[Dict]]:
        """Get candle data from Java trading engine."""
        if not self.connected or not self.client:
            return None
        try:
            response = self.client.get(
                f"/api/candles/{ticker}",
                params={"timeframe": timeframe, "limit": limit}
            )
            if response.status_code == 200:
                return response.json()
        except Exception as e:
            logger.warning(f"Failed to get candles: {e}")
        return None


# Initialize REST client
java_client = JavaApiClient()
# Try to connect on startup (non-blocking; environment-dependent — not asserted in unit tests)
try:  # pragma: no cover
    java_client.connect()
except Exception as e:  # pragma: no cover
    logger.warning(f"Could not connect to Java REST API: {e}")


# ================== Analytics Engine ==================

analytics_engine = AnalyticsEngine()


# ================== API Endpoints ==================

@app.get("/")
async def root():
    """Root endpoint."""
    return {"service": "analytics", "version": "1.0.0", "status": "running"}


@app.get("/health", response_model=HealthResponse)
async def health():
    """Health check endpoint."""
    return HealthResponse(
        status="healthy",
        service="analytics",
        version="1.0.0",
        java_grpc_connected=java_client.connected
    )


@app.post("/api/v1/indicators")
async def calculate_indicators(request: IndicatorRequest):
    """
    Calculate technical indicators on market data.

    Available indicators:
    - SMA: Simple Moving Average (period parameter)
    - EMA: Exponential Moving Average (period parameter)
    - RSI: Relative Strength Index (period parameter)
    - MACD: Moving Average Convergence Divergence (fast, slow, signal parameters)
    - BB: Bollinger Bands (period, std_mult parameters)
    - ATR: Average True Range (period parameter)
    - STOCH: Stochastic Oscillator (period parameter)
    """
    try:
        # Convert list of dicts to DataFrame
        df = pd.DataFrame(request.data)

        # Calculate indicators
        result = analytics_engine.calculate_technical_indicators(
            df=df,
            indicators=request.indicators,
            params=request.params
        )

        # JSON-safe rows (NaN/Inf → null via pandas → json)
        data_clean = json.loads(result.to_json(orient="records", date_format="iso"))
        return {
            "status": "success",
            "data": data_clean,
            "indicators_calculated": request.indicators
        }

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Error calculating indicators: {e}")
        raise HTTPException(status_code=500, detail="Internal server error")


@app.post("/api/v1/metrics")
async def calculate_metrics(request: PerformanceMetricsRequest):
    """
    Calculate performance metrics from trade history.

    Metrics returned:
    - total_trades, winning_trades, losing_trades, win_rate
    - total_pnl, avg_pnl
    - sharpe_ratio, sortino_ratio
    - max_drawdown, profit_factor
    """
    try:
        metrics = analytics_engine.calculate_performance_metrics(request.trades)

        return {
            "status": "success",
            "metrics": metrics
        }

    except Exception as e:
        logger.error(f"Error calculating metrics: {e}")
        raise HTTPException(status_code=500, detail="Internal server error")


@app.post("/api/v1/monte-carlo")
async def monte_carlo(request: MonteCarloRequest):
    """
    Run Monte Carlo simulation for strategy equity curves.

    Returns multiple simulated equity paths and statistical summary.
    """
    try:
        result = analytics_engine.run_monte_carlo_simulation(
            returns=request.returns,
            num_simulations=request.num_simulations,
            num_periods=request.num_periods
        )

        return {
            "status": "success",
            "summary": result['summary']
            # Note: In production, you might want to omit 'paths' for large simulations
            # or return them as a file/download
        }

    except Exception as e:
        logger.error(f"Error running Monte Carlo: {e}")
        raise HTTPException(status_code=500, detail="Internal server error")


@app.get("/api/v1/market-data")
async def get_market_data(
    ticker: str = Query(..., description="Ticker symbol"),
    timeframe: str = Query("5", description="Timeframe (1, 5, 15, 60, 1d, 1w)"),
    limit: int = Query(100, ge=1, le=1000, description="Number of candles")
):
    """
    Get market data from Java trading engine via REST.

    This endpoint connects to the Java trading engine and retrieves
    real-time or historical candle data.
    """
    if not java_client.connected:
        raise HTTPException(
            status_code=503,
            detail="Java trading engine not connected"
        )

    candles = java_client.get_candles(ticker, timeframe, limit)

    if candles is None:
        raise HTTPException(
            status_code=502,
            detail="Failed to retrieve candles from Java engine"
        )

    return {
        "status": "success",
        "ticker": ticker,
        "timeframe": timeframe,
        "candles": candles
    }


@app.get("/api/v1/account-status")
async def get_account_status():
    """
    Get account status from Java trading engine via REST.
    """
    if not java_client.connected:
        raise HTTPException(
            status_code=503,
            detail="Java trading engine not connected"
        )

    account = java_client.get_account_status()

    if account is None:
        raise HTTPException(
            status_code=502,
            detail="Failed to retrieve account status from Java engine"
        )

    return {
        "status": "success",
        "account": account
    }


# ================== Main Entry Point ==================

if __name__ == "__main__":  # pragma: no cover
    import uvicorn

    port = int(os.getenv('PORT', '8001'))
    uvicorn.run(
        "analytics_service.main:app",
        host="0.0.0.0",
        port=port,
        reload=os.getenv('DEBUG', 'false').lower() == 'true',
    )