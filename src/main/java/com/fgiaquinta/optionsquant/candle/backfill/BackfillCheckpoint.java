package com.fgiaquinta.optionsquant.candle.backfill;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Persists and retrieves download progress markers in SQLite.
 *
 * <p>Tracks the {@code last_chunk_end_ts} for each (ticker, timeframe) pair
 * so the backfill process can resume from where it left off.
 *
 * <p>Only active when {@code candles.store=sqlite} (default) — this bean is
 * SQLite-specific and has no meaning when the CSV rollback mode is active.
 */
@Component
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class BackfillCheckpoint {

    private static final Logger log = LoggerFactory.getLogger(BackfillCheckpoint.class);
    private final JdbcTemplate jdbc;

    public BackfillCheckpoint(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    /**
     * Returns {@code true} when every known timeframe for the given ticker has status
     * {@link BackfillStatus#SKIPPED_PERMANENT} in {@code download_progress}.
     *
     * <p>A ticker with zero progress rows is considered NOT permanently skipped —
     * it simply hasn't been attempted yet.
     *
     * <p>Used by {@code HistoricalBackfillService} to exclude dead tickers from
     * the effective ticker list unless {@code --retry-permanent-skips} is set.
     */
    public boolean isAllTimeframesPermanentlySkipped(String ticker) {
        String sql = """
            SELECT COUNT(*) = 0
            FROM download_progress
            WHERE ticker = ?
              AND status != ?
            """;
        // If there are 0 rows for this ticker (never attempted), don't exclude it
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress WHERE ticker = ?",
                Integer.class, ticker);
        if (total == null || total == 0) {
            return false;
        }
        // All rows for ticker must have SKIPPED_PERMANENT
        Integer nonSkipped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress WHERE ticker = ? AND status != ?",
                Integer.class, ticker, BackfillStatus.SKIPPED_PERMANENT.name());
        return nonSkipped != null && nonSkipped == 0;
    }

    /**
     * Retrieves the last successfully downloaded timestamp for a ticker+timeframe+origin.
     *
     * @return Optional containing the ZonedDateTime in UTC, or empty if no progress exists.
     */
    public Optional<ZonedDateTime> getLastDownloaded(String ticker, TimeFrame tf, ChunkOrigin origin) {
        String sql = "SELECT last_chunk_end_ts FROM download_progress WHERE ticker = ? AND timeframe = ? AND chunk_origin = ?";
        return jdbc.query(sql, (rs, rowNum) -> {
            long epochSeconds = rs.getLong("last_chunk_end_ts");
            return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
        }, ticker, tf.name(), origin.name()).stream().findFirst();
    }

    /**
     * Saves or updates the download progress for a ticker+timeframe with an explicit status.
     * Uses an UPSERT (REPLACE) strategy. Defaults {@code chunk_origin} to {@link ChunkOrigin#HISTORICAL}.
     */
    public void save(String ticker, TimeFrame tf, ZonedDateTime lastChunkEndTs, BackfillStatus status) {
        save(ticker, tf, lastChunkEndTs, status, ChunkOrigin.HISTORICAL, null);
    }

    /**
     * Saves or updates the download progress with explicit status and chunk origin.
     * The {@code chunk_origin} column distinguishes HISTORICAL chunks (inside hardcoded
     * critical periods) from LIVE_TAIL chunks (dynamic [last_period+1, today] window).
     */
    public void save(String ticker, TimeFrame tf, ZonedDateTime lastChunkEndTs,
                     BackfillStatus status, ChunkOrigin origin) {
        save(ticker, tf, lastChunkEndTs, status, origin, null);
    }

    /**
     * Saves or updates the download progress with explicit status, chunk origin, and skip error code.
     *
     * <p>The {@code skipErrorCode} must be non-null only when {@code status} is
     * {@link BackfillStatus#SKIPPED_PERMANENT} — it records the TWS error code that triggered
     * the permanent skip for audit purposes.
     *
     * @param skipErrorCode the TWS error code that caused the skip, or {@code null} for normal saves
     */
    public void save(String ticker, TimeFrame tf, ZonedDateTime lastChunkEndTs,
                     BackfillStatus status, ChunkOrigin origin, Integer skipErrorCode) {
        long epochSeconds = lastChunkEndTs.toEpochSecond();
        long now = Instant.now().getEpochSecond();
        log.debug("Saving checkpoint for {} {} ({}, origin={}, skipCode={}): {}",
                ticker, tf, status, origin, skipErrorCode, lastChunkEndTs);
        jdbc.update("""
            INSERT OR REPLACE INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at, chunk_origin, skip_error_code)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            ticker, tf.name(), epochSeconds, status.name(), now, origin.name(), skipErrorCode);
    }

    /**
     * @deprecated Use {@link #save(String, TimeFrame, ZonedDateTime, BackfillStatus)} instead.
     */
    @Deprecated
    public void save(String ticker, TimeFrame tf, ZonedDateTime lastChunkEndTs) {
        save(ticker, tf, lastChunkEndTs, BackfillStatus.COMPLETE_TWS);
    }
}
