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
    NEEDS_RESUME,
    /**
     * Permanent skip marker for tickers/timeframes that can never be backfilled from TWS
     * (e.g. TWS error code 200: "No security definition has been found").
     *
     * <p>Tickers with this status on ALL timeframes are excluded from future backfill runs
     * unless the {@code --retry-permanent-skips} flag is explicitly passed.
     *
     * <p>The {@code skip_error_code} column in {@code download_progress} records the TWS
     * error code that triggered this skip for audit purposes.
     */
    SKIPPED_PERMANENT
}
