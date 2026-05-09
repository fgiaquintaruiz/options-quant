package com.fgiaquinta.optionsquant.candle.backfill;

public enum BackfillStatus {
    COMPLETE_TWS,
    COMPLETE_YFINANCE,
    COMPLETE_EMPTY,
    /**
     * Marker for tickers whose checkpoint stopped at the last hardcoded critical period
     * but live data still needs to be fetched in the dynamic live-tail window.
     * Reserved for DEL2 (live-tail resume) — not yet emitted by the writer path.
     */
    NEEDS_RESUME
}
