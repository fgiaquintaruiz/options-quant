package com.fgiaquinta.optionsquant.candle.backfill;

/**
 * Origin marker for a download_progress checkpoint row.
 *
 * <ul>
 *   <li>{@link #HISTORICAL} — chunk fell inside one of the hardcoded critical periods
 *       configured in {@code candles.backfill.periods}.</li>
 *   <li>{@link #LIVE_TAIL} — chunk fell inside the dynamic live-tail window
 *       {@code [max(periods.to)+1day, now()]}, used to keep the database current
 *       beyond the last configured period.</li>
 * </ul>
 *
 * <p>Persisted in {@code download_progress.chunk_origin}.
 */
public enum ChunkOrigin {
    HISTORICAL,
    LIVE_TAIL
}
