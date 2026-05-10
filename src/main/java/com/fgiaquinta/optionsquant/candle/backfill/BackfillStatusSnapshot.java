package com.fgiaquinta.optionsquant.candle.backfill;

/**
 * Immutable snapshot of the current backfill progress state.
 * Returned by {@link BackfillProgressTracker#getStatus()} and serialized
 * as JSON by the {@code BackfillStatusController}.
 */
public record BackfillStatusSnapshot(
        String timeframe,
        int completedTickers,
        int totalTickers,
        double progressPercent,
        double velocityPerMin,
        double etaMinutes,
        int errors,
        boolean running
) {}
