package com.fgiaquinta.optionsquant.domain;

/**
 * Supported timeframes for historical data.
 */
public enum TimeFrame {
    MIN_5("5 mins", "10 D", "5min"),
    MIN_15("15 mins", "1 M", "15min"),
    HOUR_1("1 hour", "6 M", "1hour"),
    DAY_1("1 day", "1 Y", "1day");

    private final String ibkrBarSize;
    private final String ibkrDuration;
    private final String fileSuffix;

    TimeFrame(String ibkrBarSize, String ibkrDuration, String fileSuffix) {
        this.ibkrBarSize = ibkrBarSize;
        this.ibkrDuration = ibkrDuration;
        this.fileSuffix = fileSuffix;
    }

    public String getIbkrBarSize() { return ibkrBarSize; }
    public String getIbkrDuration() { return ibkrDuration; }
    public String getFileSuffix() { return fileSuffix; }

    /**
     * Creates a cache key for this ticker and timeframe combination.
     */
    public String toCacheKey(String ticker) {
        return ticker + "_" + fileSuffix;
    }
}
