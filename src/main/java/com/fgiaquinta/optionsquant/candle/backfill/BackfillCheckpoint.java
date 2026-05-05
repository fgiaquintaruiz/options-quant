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
     * Retrieves the last successfully downloaded timestamp for a ticker+timeframe.
     *
     * @return Optional containing the ZonedDateTime in UTC, or empty if no progress exists.
     */
    public Optional<ZonedDateTime> getLastDownloaded(String ticker, TimeFrame tf) {
        String sql = "SELECT last_chunk_end_ts FROM download_progress WHERE ticker = ? AND timeframe = ?";
        return jdbc.query(sql, (rs, rowNum) -> {
            long epochSeconds = rs.getLong("last_chunk_end_ts");
            return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
        }, ticker, tf.name()).stream().findFirst();
    }

    /**
     * Saves or updates the download progress for a ticker+timeframe with an explicit status.
     * Uses an UPSERT (REPLACE) strategy.
     */
    public void save(String ticker, TimeFrame tf, ZonedDateTime lastChunkEndTs, BackfillStatus status) {
        long epochSeconds = lastChunkEndTs.toEpochSecond();
        long now = Instant.now().getEpochSecond();
        log.debug("Saving checkpoint for {} {} ({}): {}", ticker, tf, status, lastChunkEndTs);
        jdbc.update("""
            INSERT OR REPLACE INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            ticker, tf.name(), epochSeconds, status.name(), now);
    }

    /**
     * @deprecated Use {@link #save(String, TimeFrame, ZonedDateTime, BackfillStatus)} instead.
     */
    @Deprecated
    public void save(String ticker, TimeFrame tf, ZonedDateTime lastChunkEndTs) {
        save(ticker, tf, lastChunkEndTs, BackfillStatus.COMPLETE_TWS);
    }
}
