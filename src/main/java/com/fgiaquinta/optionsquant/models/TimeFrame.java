package com.fgiaquinta.optionsquant.models;

public enum TimeFrame {
    MIN_1("1 min", "1 D", "1min"),
    MIN_15("15 mins", "10 D", "15min"),
    HOUR_1("1 hour", "30 D", "1hour"),
    DAY_1("1 day", "2 Y", "1day");

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
}