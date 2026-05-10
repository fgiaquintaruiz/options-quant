package com.fgiaquinta.optionsquant.candle.backfill;

/**
 * Thrown when TWS responds to a historical data request with an error code.
 *
 * <p>This exception signals a TWS-level rejection (e.g. code 200 "No security definition
 * found"), which is distinct from a legitimate empty result (zero bars returned without error).
 * Callers that receive this exception must NOT write a checkpoint — the ticker must remain
 * eligible for retry.
 */
public class IbkrHistoricalDataException extends RuntimeException {

    private final int errorCode;

    public IbkrHistoricalDataException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
