package com.fgiaquinta.optionsquant.options;

/**
 * Immutable data transfer object representing one option contract snapshot.
 *
 * <p>One row per (signalId, strike, right) tuple. A full snapshot for one ticker
 * produces 11 rows (ATM ± 5 strikes) per side, or 22 rows when both sides are recorded.
 *
 * <p>Nullable fields (bid, ask, greeks) are stored as {@code null} when the IBKR
 * callback did not fire within the timeout window.
 */
public record OptionChainSnapshotRow(
        /** UUID — correlation key linking rows from the same recording event. */
        String signalId,
        String ticker,
        /** Null for scheduled snapshots; strategy name for signal-triggered ones. */
        String strategy,
        /** {@code "CALL"} or {@code "PUT"} — direction the recording targets. */
        String direction,
        /** {@code "SIGNAL"}, {@code "SCHEDULED"}, or {@code "SCHEDULED_OPENING"}. */
        String trigger,
        /** Option expiry in {@code YYYYMMDD} format. */
        String expiry,
        double strike,
        /** {@code "C"} for call, {@code "P"} for put. */
        String right,
        /** Unix epoch seconds (UTC). */
        long snapshotTs,
        Double bid,
        Double ask,
        Double underlyingPrice,
        Double iv,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        Double optPrice,
        boolean isPaper,
        long createdAt
) {}
