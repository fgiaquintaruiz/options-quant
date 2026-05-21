package com.fgiaquinta.optionsquant.options;

import java.util.List;

/**
 * Persistence contract for option chain snapshots.
 */
public interface OptionChainSnapshotRepository {

    /**
     * Persists one option chain snapshot row.
     *
     * @param row the row to persist; must not be null
     */
    void save(OptionChainSnapshotRow row);

    /**
     * Returns all rows correlated to the given signalId, ordered by snapshot_ts DESC.
     *
     * @param signalId the UUID correlation key
     * @return list of rows; empty if none found
     */
    List<OptionChainSnapshotRow> findBySignalId(String signalId);

    /**
     * Returns all rows for the given ticker within the half-open time range
     * [{@code fromEpochSeconds}, {@code toEpochSeconds}), ordered by snapshot_ts DESC.
     *
     * @param ticker           the underlying symbol
     * @param fromEpochSeconds inclusive lower bound (epoch seconds)
     * @param toEpochSeconds   exclusive upper bound (epoch seconds)
     * @return list of rows; empty if none found
     */
    List<OptionChainSnapshotRow> findByTicker(String ticker, long fromEpochSeconds, long toEpochSeconds);
}
