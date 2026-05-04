package com.fgiaquinta.optionsquant.candle.backfill;

import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.time.ZonedDateTime;

/**
 * Represents a discrete time-bounded chunk to be downloaded for a single ticker+timeframe.
 * Used by the backfill pipeline to track progress in checkpoint-safe batches.
 */
public record TickerTimeframeChunk(
        String ticker,
        TimeFrame timeframe,
        ZonedDateTime from,
        ZonedDateTime to
) {}
