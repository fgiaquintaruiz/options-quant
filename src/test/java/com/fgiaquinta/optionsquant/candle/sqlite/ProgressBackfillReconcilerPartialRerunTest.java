package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatus;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the "partial rerun" behavior of {@link ProgressBackfillReconciler}.
 *
 * <p>Covers FASE 2 of DEL4 (recalc + overwrite policy): the reconciler must keep
 * {@code download_progress} in sync with the actual {@code candles} table for
 * (ticker, timeframe) pairs that were partially backfilled or whose progress row
 * is stale, while preserving rows whose status reflects user intent
 * ({@code NEEDS_RESUME}, {@code COMPLETE_EMPTY}).
 *
 * <p>Policy under test:
 * <ul>
 *   <li>Row matches max(ts_epoch) → no-op (do not bump updated_at).</li>
 *   <li>Row stale (last_chunk_end_ts != max) → UPDATE last_chunk_end_ts and updated_at.</li>
 *   <li>Row missing → INSERT with timeframe-aware status.</li>
 *   <li>Status IN (NEEDS_RESUME, COMPLETE_EMPTY) → preserve regardless.</li>
 * </ul>
 */
class ProgressBackfillReconcilerPartialRerunTest extends SqliteTestBase {

    private ProgressBackfillReconciler reconciler;

    @BeforeEach
    void setUpReconciler() {
        reconciler = new ProgressBackfillReconciler(ds);
    }

    // -------------------------------------------------------------------------
    // a) Row already in sync — must NOT be modified (no updated_at bump).
    // -------------------------------------------------------------------------

    @Test
    void reconciliar_filaCorrecta_noModifica() {
        long maxTs = 1000L;
        long preExistingUpdatedAt = 500L;

        insertCandle("T1", TimeFrame.DAY_1, maxTs);
        insertProgress("T1", TimeFrame.DAY_1, maxTs,
                BackfillStatus.COMPLETE_YFINANCE, preExistingUpdatedAt);

        reconciler.reconcile();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_chunk_end_ts, status, updated_at FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "T1", TimeFrame.DAY_1.name());

        assertEquals(maxTs, ((Number) row.get("last_chunk_end_ts")).longValue(),
                "last_chunk_end_ts must be unchanged when already in sync");
        assertEquals(BackfillStatus.COMPLETE_YFINANCE.name(), row.get("status"),
                "status must be preserved when already in sync");
        assertEquals(preExistingUpdatedAt, ((Number) row.get("updated_at")).longValue(),
                "updated_at must NOT be bumped when row is already in sync (no-op)");
    }

    // -------------------------------------------------------------------------
    // b) Row stale — must be updated (last_chunk_end_ts AND updated_at).
    // -------------------------------------------------------------------------

    @Test
    void reconciliar_filaStale_actualizaLastChunkEndTs() {
        long maxTs = 2000L;
        long staleTs = 1500L;
        long preExistingUpdatedAt = 500L;

        insertCandle("T2", TimeFrame.HOUR_1, maxTs);
        insertProgress("T2", TimeFrame.HOUR_1, staleTs,
                BackfillStatus.COMPLETE_TWS, preExistingUpdatedAt);

        reconciler.reconcile();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_chunk_end_ts, status, updated_at FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "T2", TimeFrame.HOUR_1.name());

        assertEquals(maxTs, ((Number) row.get("last_chunk_end_ts")).longValue(),
                "last_chunk_end_ts must be updated to MAX(candles.ts_epoch)");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(), row.get("status"),
                "status must be preserved (only the timestamp changed)");
        assertTrue(((Number) row.get("updated_at")).longValue() > preExistingUpdatedAt,
                "updated_at must be bumped when row is stale");
    }

    // -------------------------------------------------------------------------
    // c) Missing row — INSERT with status driven by timeframe.
    // -------------------------------------------------------------------------

    @Test
    void reconciliar_filaFaltante_insertaConStatusCorrecto() {
        insertCandle("T3", TimeFrame.DAY_1, 1000L);
        insertCandle("T3", TimeFrame.HOUR_1, 1100L);
        insertCandle("T3", TimeFrame.MIN_15, 1200L);
        insertCandle("T3", TimeFrame.MIN_5, 1300L);

        reconciler.reconcile();

        assertEquals(BackfillStatus.COMPLETE_YFINANCE.name(),
                statusFor("T3", TimeFrame.DAY_1),
                "DAY_1 must be inserted as COMPLETE_YFINANCE");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(),
                statusFor("T3", TimeFrame.HOUR_1),
                "HOUR_1 must be inserted as COMPLETE_TWS");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(),
                statusFor("T3", TimeFrame.MIN_15),
                "MIN_15 must be inserted as COMPLETE_TWS");
        assertEquals(BackfillStatus.COMPLETE_TWS.name(),
                statusFor("T3", TimeFrame.MIN_5),
                "MIN_5 must be inserted as COMPLETE_TWS");

        // last_chunk_end_ts must equal MAX(ts_epoch) per timeframe
        assertEquals(1000L, lastChunkEndFor("T3", TimeFrame.DAY_1));
        assertEquals(1100L, lastChunkEndFor("T3", TimeFrame.HOUR_1));
        assertEquals(1200L, lastChunkEndFor("T3", TimeFrame.MIN_15));
        assertEquals(1300L, lastChunkEndFor("T3", TimeFrame.MIN_5));
    }

    // -------------------------------------------------------------------------
    // d) Mixed realistic state — correct + stale + missing + protected.
    //    CRITICAL: NEEDS_RESUME and COMPLETE_EMPTY rows must be preserved.
    // -------------------------------------------------------------------------

    @Test
    void reconciliar_estadoMixto_510PorTimeframe() {
        long preExistingUpdatedAt = 500L;

        // --- Group A: row in sync (no-op expected) -----------------------------
        insertCandle("A1", TimeFrame.DAY_1, 1000L);
        insertProgress("A1", TimeFrame.DAY_1, 1000L,
                BackfillStatus.COMPLETE_YFINANCE, preExistingUpdatedAt);

        // --- Group B: row stale (update expected) ------------------------------
        insertCandle("B1", TimeFrame.HOUR_1, 2000L);
        insertProgress("B1", TimeFrame.HOUR_1, 1500L,
                BackfillStatus.COMPLETE_TWS, preExistingUpdatedAt);

        insertCandle("B2", TimeFrame.MIN_15, 3000L);
        insertProgress("B2", TimeFrame.MIN_15, 2500L,
                BackfillStatus.COMPLETE_TWS, preExistingUpdatedAt);

        // --- Group C: missing (insert expected) --------------------------------
        insertCandle("C1", TimeFrame.MIN_5, 4000L);
        insertCandle("C2", TimeFrame.DAY_1, 1500L);

        // --- Group D: protected — NEEDS_RESUME (must NOT be modified) ----------
        insertCandle("D1", TimeFrame.DAY_1, 9999L); // candles ahead of progress
        insertProgress("D1", TimeFrame.DAY_1, 1234L,
                BackfillStatus.NEEDS_RESUME, preExistingUpdatedAt);

        // --- Group E: protected — COMPLETE_EMPTY (must NOT be modified) --------
        insertCandle("E1", TimeFrame.DAY_1, 8888L);
        insertProgress("E1", TimeFrame.DAY_1, 1L,
                BackfillStatus.COMPLETE_EMPTY, preExistingUpdatedAt);

        // ---- Run --------------------------------------------------------------
        reconciler.reconcile();

        // ---- Assertions -------------------------------------------------------

        // A: in sync — untouched
        Map<String, Object> a1 = rowFor("A1", TimeFrame.DAY_1);
        assertEquals(1000L, ((Number) a1.get("last_chunk_end_ts")).longValue());
        assertEquals(preExistingUpdatedAt, ((Number) a1.get("updated_at")).longValue(),
                "A1 in sync must not bump updated_at");

        // B: stale — updated
        Map<String, Object> b1 = rowFor("B1", TimeFrame.HOUR_1);
        assertEquals(2000L, ((Number) b1.get("last_chunk_end_ts")).longValue());
        assertTrue(((Number) b1.get("updated_at")).longValue() > preExistingUpdatedAt,
                "B1 stale must bump updated_at");

        Map<String, Object> b2 = rowFor("B2", TimeFrame.MIN_15);
        assertEquals(3000L, ((Number) b2.get("last_chunk_end_ts")).longValue());

        // C: missing — inserted with correct status
        assertEquals(BackfillStatus.COMPLETE_TWS.name(), statusFor("C1", TimeFrame.MIN_5));
        assertEquals(4000L, lastChunkEndFor("C1", TimeFrame.MIN_5));
        assertEquals(BackfillStatus.COMPLETE_YFINANCE.name(), statusFor("C2", TimeFrame.DAY_1));
        assertEquals(1500L, lastChunkEndFor("C2", TimeFrame.DAY_1));

        // D: NEEDS_RESUME — preserved (status, ts, updated_at)
        Map<String, Object> d1 = rowFor("D1", TimeFrame.DAY_1);
        assertEquals(BackfillStatus.NEEDS_RESUME.name(), d1.get("status"),
                "NEEDS_RESUME status must be preserved");
        assertEquals(1234L, ((Number) d1.get("last_chunk_end_ts")).longValue(),
                "NEEDS_RESUME last_chunk_end_ts must be preserved (do NOT recalc)");
        assertEquals(preExistingUpdatedAt, ((Number) d1.get("updated_at")).longValue(),
                "NEEDS_RESUME updated_at must be preserved");

        // E: COMPLETE_EMPTY — preserved (status, ts, updated_at)
        Map<String, Object> e1 = rowFor("E1", TimeFrame.DAY_1);
        assertEquals(BackfillStatus.COMPLETE_EMPTY.name(), e1.get("status"),
                "COMPLETE_EMPTY status must be preserved");
        assertEquals(1L, ((Number) e1.get("last_chunk_end_ts")).longValue(),
                "COMPLETE_EMPTY last_chunk_end_ts must be preserved (do NOT recalc)");
        assertEquals(preExistingUpdatedAt, ((Number) e1.get("updated_at")).longValue(),
                "COMPLETE_EMPTY updated_at must be preserved");

        // No UNIQUE constraint violations / no row duplication
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress", Long.class);
        // A1 + B1 + B2 + C1 + C2 + D1 + E1 = 7
        assertEquals(7L, total,
                "Row count must be exactly 7 — no duplicates, no UNIQUE violations");

        // Idempotency check: rerunning must keep the row count and not bump A1/D1/E1.
        reconciler.reconcile();
        Long totalAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress", Long.class);
        assertEquals(7L, totalAfter, "Row count must remain 7 after a second reconcile");

        Map<String, Object> a1After = rowFor("A1", TimeFrame.DAY_1);
        assertEquals(preExistingUpdatedAt, ((Number) a1After.get("updated_at")).longValue(),
                "A1 must remain idempotent across reruns");
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

    private void insertProgress(String ticker, TimeFrame tf, long lastChunkEndTs,
                                BackfillStatus status, long updatedAt) {
        jdbc.update("""
                INSERT INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                ticker, tf.name(), lastChunkEndTs, status.name(), updatedAt);
    }

    private String statusFor(String ticker, TimeFrame tf) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM download_progress WHERE ticker = ? AND timeframe = ?",
                ticker, tf.name());
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("status");
    }

    private long lastChunkEndFor(String ticker, TimeFrame tf) {
        return ((Number) jdbc.queryForMap(
                "SELECT last_chunk_end_ts FROM download_progress WHERE ticker = ? AND timeframe = ?",
                ticker, tf.name()).get("last_chunk_end_ts")).longValue();
    }

    private Map<String, Object> rowFor(String ticker, TimeFrame tf) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_chunk_end_ts, status, updated_at FROM download_progress WHERE ticker = ? AND timeframe = ?",
                ticker, tf.name());
        assertNotNull(row, "Row must exist for " + ticker + "/" + tf);
        return row;
    }
}
