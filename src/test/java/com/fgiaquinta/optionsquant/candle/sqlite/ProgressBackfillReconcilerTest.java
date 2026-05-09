package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatus;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProgressBackfillReconciler}.
 *
 * <p>Validates that the reconciler correctly populates {@code download_progress}
 * for tickers that have candles in the database but no corresponding progress row.
 *
 * <p>Uses in-memory SQLite via {@link SqliteTestBase}.
 */
class ProgressBackfillReconcilerTest extends SqliteTestBase {

    private ProgressBackfillReconciler reconciler;

    @BeforeEach
    void setUpReconciler() {
        reconciler = new ProgressBackfillReconciler(ds);
    }

    // -------------------------------------------------------------------------
    // T1 — adds missing progress row for ticker with candles but no progress
    // -------------------------------------------------------------------------

    @Test
    void reconcile_addsMissingProgressForTickerWithCandlesButNoProgress() {
        long ts1 = 1700000000L;
        long ts2 = 1700086400L; // +1 day → max
        insertCandle("AAPL", TimeFrame.DAY_1, ts1);
        insertCandle("AAPL", TimeFrame.DAY_1, ts2);

        int inserted = reconciler.reconcile();

        assertEquals(1, inserted, "Should have inserted 1 missing progress row");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ticker, timeframe, last_chunk_end_ts, status FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "AAPL", TimeFrame.DAY_1.name());

        assertEquals("AAPL", row.get("ticker"));
        assertEquals(TimeFrame.DAY_1.name(), row.get("timeframe"));
        assertEquals(ts2, ((Number) row.get("last_chunk_end_ts")).longValue(),
                "last_chunk_end_ts must be max(ts_epoch) from candles");
        assertEquals(BackfillStatus.COMPLETE_YFINANCE.name(), row.get("status"));
    }

    // -------------------------------------------------------------------------
    // T2 — does not overwrite existing progress entries
    // -------------------------------------------------------------------------

    @Test
    void reconcile_doesNotOverwriteExistingProgressEntries() {
        // Pre-existing progress with a deliberately weird status to detect overwrite
        long preExistingTs = 1600000000L;
        long now = Instant.now().getEpochSecond();
        jdbc.update("""
                INSERT INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                "MSFT", TimeFrame.DAY_1.name(), preExistingTs, BackfillStatus.COMPLETE_EMPTY.name(), now);

        // Insert candles that would otherwise generate progress
        insertCandle("MSFT", TimeFrame.DAY_1, 1700000000L);
        insertCandle("MSFT", TimeFrame.DAY_1, 1800000000L);

        int inserted = reconciler.reconcile();

        assertEquals(0, inserted, "Should not insert anything because progress already exists");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_chunk_end_ts, status FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "MSFT", TimeFrame.DAY_1.name());

        assertEquals(preExistingTs, ((Number) row.get("last_chunk_end_ts")).longValue(),
                "Existing last_chunk_end_ts must be preserved");
        assertEquals(BackfillStatus.COMPLETE_EMPTY.name(), row.get("status"),
                "Existing status must be preserved");
    }

    // -------------------------------------------------------------------------
    // T3 — assigns correct status by timeframe
    // -------------------------------------------------------------------------

    @Test
    void reconcile_assignsCorrectStatusByTimeframe() {
        insertCandle("NVDA", TimeFrame.DAY_1, 1700000000L);
        insertCandle("NVDA", TimeFrame.HOUR_1, 1700000000L);
        insertCandle("NVDA", TimeFrame.MIN_15, 1700000000L);
        insertCandle("NVDA", TimeFrame.MIN_5, 1700000000L);

        int inserted = reconciler.reconcile();

        assertEquals(4, inserted);

        assertEquals(BackfillStatus.COMPLETE_YFINANCE.name(),
                statusFor("NVDA", TimeFrame.DAY_1),
                "DAY_1 must be assigned COMPLETE_YFINANCE");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(),
                statusFor("NVDA", TimeFrame.HOUR_1),
                "HOUR_1 must be assigned COMPLETE_TWS");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(),
                statusFor("NVDA", TimeFrame.MIN_15),
                "MIN_15 must be assigned COMPLETE_TWS");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(),
                statusFor("NVDA", TimeFrame.MIN_5),
                "MIN_5 must be assigned COMPLETE_TWS");
    }

    // -------------------------------------------------------------------------
    // T4 — idempotent: running twice produces same result as running once
    // -------------------------------------------------------------------------

    @Test
    void reconcile_isIdempotent() {
        insertCandle("GOOGL", TimeFrame.DAY_1, 1700000000L);
        insertCandle("GOOGL", TimeFrame.HOUR_1, 1700000000L);

        int firstRun = reconciler.reconcile();
        int secondRun = reconciler.reconcile();

        assertEquals(2, firstRun, "First run must insert 2 rows");
        assertEquals(0, secondRun, "Second run must insert 0 rows (idempotent)");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress WHERE ticker = ?",
                Long.class, "GOOGL");
        assertEquals(2L, count, "Total rows must remain 2 after two runs");
    }

    // -------------------------------------------------------------------------
    // T5 — skips ticker with no candles
    // -------------------------------------------------------------------------

    @Test
    void reconcile_skipsTickerWithNoCandles() {
        // Empty database — no candles, no progress
        int inserted = reconciler.reconcile();

        assertEquals(0, inserted, "Empty DB must produce no inserts");

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM download_progress", Long.class);
        assertEquals(0L, count, "No progress rows must be created");
    }

    // -------------------------------------------------------------------------
    // T6 — mixed scenario: some tickers with progress, some without
    // -------------------------------------------------------------------------

    @Test
    void reconcile_mixedScenario_onlyMissingTickersInserted() {
        // AAPL: has candles AND a STALE progress row (status COMPLETE_YFINANCE, not protected)
        //       → DEL4 policy: last_chunk_end_ts MUST be recalc'd to MAX(candles.ts_epoch).
        // TSLA: has candles, NO progress → should be inserted.
        long now = Instant.now().getEpochSecond();
        insertCandle("AAPL", TimeFrame.DAY_1, 1700000000L);
        jdbc.update("""
                INSERT INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                "AAPL", TimeFrame.DAY_1.name(), 1600000000L, BackfillStatus.COMPLETE_YFINANCE.name(), now);

        insertCandle("TSLA", TimeFrame.DAY_1, 1750000000L);

        int inserted = reconciler.reconcile();

        assertEquals(1, inserted, "Only TSLA should be inserted (AAPL row already exists, will be UPDATEd)");

        // AAPL stale row recalc'd to MAX(candles.ts_epoch) per DEL4 policy
        assertEquals(1700000000L, ((Number) jdbc.queryForMap(
                "SELECT last_chunk_end_ts FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "AAPL", TimeFrame.DAY_1.name()).get("last_chunk_end_ts")).longValue(),
                "AAPL stale last_chunk_end_ts must be updated to MAX(candles.ts_epoch)");

        // TSLA inserted with correct values
        assertEquals(1750000000L, ((Number) jdbc.queryForMap(
                "SELECT last_chunk_end_ts FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "TSLA", TimeFrame.DAY_1.name()).get("last_chunk_end_ts")).longValue());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertCandle(String ticker, TimeFrame tf, long tsEpoch) {
        jdbc.update("""
                INSERT INTO candles (ticker, timeframe, ts_epoch, open, high, low, close, volume)
                VALUES (?, ?, ?, 1.0, 2.0, 0.5, 1.5, 1000)
                """,
                ticker, tf.name(), tsEpoch);
    }

    private String statusFor(String ticker, TimeFrame tf) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM download_progress WHERE ticker = ? AND timeframe = ?",
                ticker, tf.name());
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("status");
    }
}
