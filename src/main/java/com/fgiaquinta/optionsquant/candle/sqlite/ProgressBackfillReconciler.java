package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatus;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * Data reconciler that keeps {@code download_progress} consistent with the actual
 * contents of the {@code candles} table.
 *
 * <p>This was originally introduced to retroactively populate progress rows for
 * tickers backfilled before the progress marker existed. With DEL4 it was extended
 * to also recalc rows whose {@code last_chunk_end_ts} drifted from the candle data
 * (partial rerun scenario), while preserving rows whose status reflects user intent.
 *
 * <p>Per (ticker, timeframe) pair that has candles, the reconciliation policy is:
 * <ul>
 *   <li>Status IN ({@link BackfillStatus#NEEDS_RESUME}, {@link BackfillStatus#COMPLETE_EMPTY})
 *       → preserve as-is (do not recalc, do not bump {@code updated_at}).</li>
 *   <li>Row exists and {@code last_chunk_end_ts == max(candles.ts_epoch)} → no-op.</li>
 *   <li>Row exists and {@code last_chunk_end_ts != max(candles.ts_epoch)} →
 *       UPDATE {@code last_chunk_end_ts} and bump {@code updated_at}; status preserved.</li>
 *   <li>Row missing → INSERT with timeframe-aware status:
 *     <ul>
 *       <li>{@link TimeFrame#DAY_1} → {@link BackfillStatus#COMPLETE_YFINANCE}</li>
 *       <li>{@link TimeFrame#HOUR_1}, {@link TimeFrame#MIN_15}, {@link TimeFrame#MIN_5}
 *           → {@link BackfillStatus#COMPLETE_TWS}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Idempotent — running multiple times produces the same result as running once.
 *
 * <p>Runs automatically once on application startup via {@link ApplicationReadyEvent}.
 */
@Component
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class ProgressBackfillReconciler {

    private static final Logger log = LoggerFactory.getLogger(ProgressBackfillReconciler.class);

    private final JdbcTemplate jdbc;

    public ProgressBackfillReconciler(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    /**
     * Triggered once on application startup. Catches any exception so a reconciliation
     * failure never prevents the application from coming up.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            ReconcileResult result = reconcileWithStats();
            log.info("ProgressBackfillReconciler: inserted={}, updated={} (stale rows), preserved={} (NEEDS_RESUME/COMPLETE_EMPTY).",
                    result.inserted(), result.updated(), result.preserved());
        } catch (Exception e) {
            log.error("ProgressBackfillReconciler: failed to reconcile progress, continuing startup.", e);
        }
    }

    /**
     * Runs the reconciliation policy described in the class-level Javadoc.
     *
     * @return number of rows inserted (legacy contract — kept for the existing test suite).
     */
    public int reconcile() {
        ReconcileResult r = doReconcile();
        return r.inserted();
    }

    /**
     * Full reconciliation result, exposing inserted / updated / preserved counts.
     */
    public ReconcileResult reconcileWithStats() {
        return doReconcile();
    }

    private ReconcileResult doReconcile() {
        long now = Instant.now().getEpochSecond();

        // Build the timeframe → status CASE expression dynamically so any future
        // timeframe addition surfaces here as a missing branch.
        String statusCase = """
                CASE c.timeframe
                    WHEN '%s' THEN '%s'
                    WHEN '%s' THEN '%s'
                    WHEN '%s' THEN '%s'
                    WHEN '%s' THEN '%s'
                END
                """.formatted(
                TimeFrame.DAY_1.name(),  BackfillStatus.COMPLETE_YFINANCE.name(),
                TimeFrame.HOUR_1.name(), BackfillStatus.COMPLETE_TWS.name(),
                TimeFrame.MIN_15.name(), BackfillStatus.COMPLETE_TWS.name(),
                TimeFrame.MIN_5.name(),  BackfillStatus.COMPLETE_TWS.name()
        );

        // -- INSERT: pairs that have candles but no progress row at all. ---------
        String insertSql = """
                INSERT INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at)
                SELECT
                    c.ticker,
                    c.timeframe,
                    MAX(c.ts_epoch) AS last_chunk_end_ts,
                    %s AS status,
                    ? AS updated_at
                FROM candles c
                LEFT JOIN download_progress p
                    ON p.ticker = c.ticker AND p.timeframe = c.timeframe
                WHERE p.ticker IS NULL
                GROUP BY c.ticker, c.timeframe
                """.formatted(statusCase);

        int inserted = jdbc.update(insertSql, now);

        // -- UPDATE: existing rows whose last_chunk_end_ts drifted from the candle
        //    truth, EXCLUDING protected statuses (NEEDS_RESUME, COMPLETE_EMPTY).
        //    Status is preserved on update — only the timestamp and updated_at change.
        String updateSql = """
                UPDATE download_progress
                SET last_chunk_end_ts = (
                        SELECT MAX(c.ts_epoch)
                        FROM candles c
                        WHERE c.ticker = download_progress.ticker
                          AND c.timeframe = download_progress.timeframe
                    ),
                    updated_at = ?
                WHERE status NOT IN (?, ?)
                  AND EXISTS (
                        SELECT 1 FROM candles c
                        WHERE c.ticker = download_progress.ticker
                          AND c.timeframe = download_progress.timeframe
                    )
                  AND last_chunk_end_ts != (
                        SELECT MAX(c.ts_epoch)
                        FROM candles c
                        WHERE c.ticker = download_progress.ticker
                          AND c.timeframe = download_progress.timeframe
                    )
                """;

        int updated = jdbc.update(updateSql,
                now,
                BackfillStatus.NEEDS_RESUME.name(),
                BackfillStatus.COMPLETE_EMPTY.name());

        // -- Stats only: how many protected rows we left alone (for logging). ----
        Integer preserved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress WHERE status IN (?, ?)",
                Integer.class,
                BackfillStatus.NEEDS_RESUME.name(),
                BackfillStatus.COMPLETE_EMPTY.name());

        return new ReconcileResult(inserted, updated, preserved == null ? 0 : preserved);
    }

    /**
     * Result of a reconciliation pass.
     *
     * @param inserted  rows inserted (missing → present)
     * @param updated   rows whose stale {@code last_chunk_end_ts} was bumped to MAX(ts_epoch)
     * @param preserved rows currently in protected status (NEEDS_RESUME / COMPLETE_EMPTY)
     */
    public record ReconcileResult(int inserted, int updated, int preserved) { }
}
