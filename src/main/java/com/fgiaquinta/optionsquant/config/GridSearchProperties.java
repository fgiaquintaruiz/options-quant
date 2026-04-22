package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for {@link com.fgiaquinta.optionsquant.backtest.grid.GridSearchService}.
 */
@ConfigurationProperties(prefix = "grid-search")
public class GridSearchProperties {

    /**
     * Maximum number of backtest cells per request (Cartesian product of axis values).
     */
    private int maxCells = 500;

    /**
     * Wall-clock cap for the whole grid run (or entire walk-forward chain); 0 = no limit.
     * Default 1h avoids runaway CPU on large grids; set 0 in YAML to disable.
     */
    private long timeoutMs = 3_600_000L;

    public int getMaxCells() {
        return maxCells;
    }

    public void setMaxCells(int maxCells) {
        this.maxCells = maxCells;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
