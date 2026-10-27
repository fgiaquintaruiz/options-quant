package com.fgiaquinta.optionsquant.options;

import java.util.List;

/**
 * Selects the strike window to record for an option chain: the 5 strikes below and the
 * 5 above the underlying price (plus the matching strike when the price sits exactly on one).
 *
 * <h2>Internals</h2>
 * TODO (author): what happens inside, not what the code does.
 *
 * <h2>Anchor</h2>
 * TODO (author): one sentence, your own image for the mechanism.
 */
public final class StrikeWindow {

    public List<Double> select(final List<Double> strikes, final Double underlyingPrice) {
        throw new UnsupportedOperationException("TODO: exercise 1");
    }
}
