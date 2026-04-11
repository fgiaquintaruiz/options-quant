package com.fgiaquinta.optionsquant.models;

public enum TimeFrame {
    MIN_5("5 mins", "10 D", "5min"),    // Reemplazado 1min por 5min
    MIN_15("15 mins", "1 M", "15min"),  // 1 mes de historia para 15m
    HOUR_1("1 hour", "6 M", "1hour"),   // 6 meses para que la SMA200 sea exacta
    DAY_1("1 day", "2 Y", "1day");      // 2 años para diario

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