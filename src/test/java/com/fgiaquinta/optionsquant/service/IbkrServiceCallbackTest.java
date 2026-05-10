package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.backfill.IbkrHistoricalDataException;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IbkrService TWS error-propagation behavior (T1, T3, T4, T5).
 *
 * <p>Strategy: uses package-private test hooks on IbkrService to exercise the
 * waitForRequestCompletion → failedRequests → IbkrHistoricalDataException path
 * without a real TWS connection.
 *
 * <ul>
 *   <li>simulatePendingRequest() — registers a reqId as if reqHistoricalData had been sent
 *   <li>simulateWaitForResult() — blocks until a callback fires, then throws or returns
 *   <li>simulateTwsError() — fires onErrorReceived for a given reqId
 *   <li>simulateTwsRequestComplete() — fires onRequestComplete for a given reqId
 * </ul>
 */
class IbkrServiceCallbackTest {

    private IbkrService ibkrService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        IbkrProperties props = new IbkrProperties(
                "localhost", 7497, 30,
                List.of(), false, "DU000001",
                100, 0.01, 20, 1, 2, 999
        );
        MetricsService metrics = new MetricsService(new SimpleMeterRegistry());
        ibkrService = new IbkrService(props, metrics);
        executor = Executors.newSingleThreadExecutor();
    }

    // -------------------------------------------------------------------------
    // T1: TWS fires error code=200 → simulateWaitForResult throws IbkrHistoricalDataException(200)
    // -------------------------------------------------------------------------

    @Test
    void whenTwsReturnsError200_waitForResultThrowsIbkrHistoricalDataException() throws Exception {
        int reqId = ibkrService.simulatePendingRequest("AAPL", TimeFrame.MIN_15);

        Future<List<Candle>> future = executor.submit(() ->
                ibkrService.simulateWaitForResult(reqId, "AAPL", TimeFrame.MIN_15)
        );

        // Give the background thread time to enter the polling loop
        Thread.sleep(100);
        ibkrService.simulateTwsError(reqId, 200, "No security definition has been found");

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IbkrHistoricalDataException.class, ex.getCause());
        assertEquals(200, ((IbkrHistoricalDataException) ex.getCause()).getErrorCode());
    }

    // -------------------------------------------------------------------------
    // T3: TWS fires error code=162 → simulateWaitForResult throws IbkrHistoricalDataException(162)
    // -------------------------------------------------------------------------

    @Test
    void whenTwsReturnsError162_waitForResultThrowsIbkrHistoricalDataException() throws Exception {
        int reqId = ibkrService.simulatePendingRequest("MSFT", TimeFrame.HOUR_1);

        Future<List<Candle>> future = executor.submit(() ->
                ibkrService.simulateWaitForResult(reqId, "MSFT", TimeFrame.HOUR_1)
        );

        Thread.sleep(100);
        ibkrService.simulateTwsError(reqId, 162, "Historical Market Data Service error message:no data");

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IbkrHistoricalDataException.class, ex.getCause());
        assertEquals(162, ((IbkrHistoricalDataException) ex.getCause()).getErrorCode());
    }

    // -------------------------------------------------------------------------
    // T4: TWS fires unknown error code=321 → simulateWaitForResult throws IbkrHistoricalDataException(321)
    // -------------------------------------------------------------------------

    @Test
    void whenTwsReturnsUnknownError321_waitForResultThrowsIbkrHistoricalDataException() throws Exception {
        int reqId = ibkrService.simulatePendingRequest("NVDA", TimeFrame.MIN_5);

        Future<List<Candle>> future = executor.submit(() ->
                ibkrService.simulateWaitForResult(reqId, "NVDA", TimeFrame.MIN_5)
        );

        Thread.sleep(100);
        ibkrService.simulateTwsError(reqId, 321, "Error validating request");

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IbkrHistoricalDataException.class, ex.getCause());
        assertEquals(321, ((IbkrHistoricalDataException) ex.getCause()).getErrorCode());
    }

    // -------------------------------------------------------------------------
    // T5: historicalDataEnd with 0 bars, no prior error → returns empty list, NO exception
    // -------------------------------------------------------------------------

    @Test
    void whenTwsReturnsZeroBarsWithNoError_returnsEmptyListWithoutException() throws Exception {
        int reqId = ibkrService.simulatePendingRequest("TSLA", TimeFrame.DAY_1);

        Future<List<Candle>> future = executor.submit(() ->
                ibkrService.simulateWaitForResult(reqId, "TSLA", TimeFrame.DAY_1)
        );

        Thread.sleep(100);
        // Fire historicalDataEnd with no bars and no prior error callback
        ibkrService.simulateTwsRequestComplete(reqId);

        List<Candle> result = future.get();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected empty list when TWS returns no bars without error");
    }
}
