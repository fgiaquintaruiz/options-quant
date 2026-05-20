package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.Candle;

import java.time.ZonedDateTime;

/**
 * Shared test factory for creating {@link Candle} instances across strategy logging tests.
 *
 * <p>Eliminates copy-pasted {@code candle()} helper methods in each test class.
 * Use via static import:
 * <pre>{@code
 * import static com.fgiaquinta.optionsquant.strategy.CandleTestFactory.candle;
 * }</pre>
 */
public final class CandleTestFactory {

    private CandleTestFactory() {
        // utility — no instances
    }

    /**
     * Creates a {@link Candle} with the given OHLCV values.
     *
     * @param time   bar timestamp
     * @param open   open price
     * @param high   high price
     * @param low    low price
     * @param close  close price
     * @param volume bar volume
     * @return a fully initialised {@link Candle}
     */
    public static Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }
}
