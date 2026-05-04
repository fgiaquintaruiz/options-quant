package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Port for reading and writing historical OHLCV candle data.
 * Implementations may back this by SQLite, in-memory cache, or other stores.
 */
public interface CandleRepository {

    /** Returns all candles for the given ticker and timeframe, ordered by timestamp ascending. */
    List<Candle> load(String ticker, TimeFrame tf);

    /** Returns candles in [from, to) — from inclusive, to exclusive — ordered by timestamp ascending. */
    List<Candle> loadRange(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to);

    /** Returns the most recent timestamp stored for the given ticker+timeframe, or empty if none. */
    Optional<ZonedDateTime> lastTimestamp(String ticker, TimeFrame tf);

    /** Upserts (insert-or-replace) the given candles for the given ticker+timeframe. */
    void upsert(String ticker, TimeFrame tf, List<Candle> candles);

    /** Returns true if at least one candle is stored for the given ticker+timeframe. */
    boolean hasLocalData(String ticker, TimeFrame tf);

    /** Streams all candles for the given ticker and timeframe. */
    Stream<Candle> stream(String ticker, TimeFrame tf);
}
