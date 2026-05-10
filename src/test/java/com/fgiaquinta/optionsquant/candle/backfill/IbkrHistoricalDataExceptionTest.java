package com.fgiaquinta.optionsquant.candle.backfill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IbkrHistoricalDataException.
 *
 * <p>Verifies that the exception carries the TWS error code and message correctly.
 */
class IbkrHistoricalDataExceptionTest {

    @Test
    void constructor_storesErrorCodeAndMessage() {
        var ex = new IbkrHistoricalDataException(200, "No security definition has been found");

        assertEquals(200, ex.getErrorCode());
        assertEquals("No security definition has been found", ex.getMessage());
    }

    @Test
    void isRuntimeException() {
        var ex = new IbkrHistoricalDataException(162, "Historical Market Data Service error message");

        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void differentErrorCodes_areStoredCorrectly() {
        assertEquals(162, new IbkrHistoricalDataException(162, "msg").getErrorCode());
        assertEquals(321, new IbkrHistoricalDataException(321, "msg").getErrorCode());
        assertEquals(200, new IbkrHistoricalDataException(200, "msg").getErrorCode());
    }
}
