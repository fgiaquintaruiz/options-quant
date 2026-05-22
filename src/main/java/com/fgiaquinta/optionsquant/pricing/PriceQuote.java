package com.fgiaquinta.optionsquant.pricing;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of an underlying price at a point in time.
 */
public record PriceQuote(double price, Instant asOf) {

    public PriceQuote {
        Objects.requireNonNull(asOf, "asOf");
        if (price <= 0) {
            throw new IllegalArgumentException("price must be > 0, got " + price);
        }
    }
}
