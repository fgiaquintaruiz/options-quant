package com.fgiaquinta.optionsquant.pricing;

import java.time.Duration;

/**
 * Port for fetching the current underlying price of a ticker.
 * Implementations may cache and may block on a market-data round-trip.
 * Implementations must be safe to call concurrently from multiple threads.
 */
public interface UnderlyingPriceGateway {

    /**
     * Returns the latest known price for {@code ticker} if it is no older than {@code maxAge}.
     * Returns {@code null} if no fresh quote is available within the implementation's timeout.
     *
     * <p>{@code maxAge} bounds cache staleness; implementations may apply an internal
     * request timeout when no fresh cached quote exists.
     *
     * @param ticker symbol, e.g. "SPY"
     * @param maxAge maximum acceptable staleness of the returned quote
     * @return a {@link PriceQuote} no older than {@code maxAge}, or {@code null} if unavailable
     */
    PriceQuote get(String ticker, Duration maxAge);
}
