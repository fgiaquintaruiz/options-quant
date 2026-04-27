package com.fgiaquinta.optionsquant.dto;

import com.ib.client.Contract;

import java.time.Instant;

/**
 * Immutable snapshot of a single account position received from the IBKR EWrapper
 * {@code position()} callback. Keyed by {@code symbol} in the AccountManager snapshot map.
 *
 * <p>Only STK and OPT secTypes are stored; all other contract types are filtered at callback time.
 *
 * @param symbol      Ticker symbol (e.g. "NVDA", "SPY")
 * @param secType     Contract type: "STK" or "OPT"
 * @param contract    Full IBKR {@link Contract} object (contains conId, exchange, expiry, strike…).
 *                    NOTE: contract.secType() returns {@link com.ib.client.Types.SecType} enum, not String —
 *                    use {@code snapshot.secType()} (String "STK"/"OPT") for filtering or comparison.
 * @param quantity    Number of shares / contracts held (positive = long)
 * @param avgCost     Average cost basis per share / contract reported by TWS
 * @param snapshotAt  Instant when this snapshot entry was last written
 */
public record PositionSnapshot(
        String symbol,
        String secType,
        Contract contract,
        int quantity,
        double avgCost,
        Instant snapshotAt
) {
}
