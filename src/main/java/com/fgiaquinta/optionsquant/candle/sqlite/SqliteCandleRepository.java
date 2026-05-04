package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.candle.RepositoryException;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * SQLite-backed implementation of {@link CandleRepository}.
 *
 * <p>Two DataSource pools are used:
 * <ul>
 *   <li>{@code writeDs} (pool size=1, WAL pragmas) — for all INSERTs/UPDATEs.</li>
 *   <li>{@code readDs} (pool size=8) — for SELECTs and streaming reads.</li>
 * </ul>
 *
 * <p>Active when {@code candles.store=sqlite} (default).
 */
@Repository
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class SqliteCandleRepository implements CandleRepository {

    private static final int BATCH_SIZE = 2000;

    private static final String UPSERT_CANDLE_SQL = """
            INSERT INTO candles(ticker,timeframe,ts_epoch,open,high,low,close,volume)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(ticker,timeframe,ts_epoch) DO UPDATE SET
                open=excluded.open,
                high=excluded.high,
                low=excluded.low,
                close=excluded.close,
                volume=excluded.volume
            """;

    private static final String UPSERT_INGEST_LOG_SQL = """
            INSERT INTO ingest_log(ticker,timeframe,last_ts_epoch,rows,updated_at)
            VALUES(?,?,?,?,?)
            ON CONFLICT(ticker,timeframe) DO UPDATE SET
                last_ts_epoch=excluded.last_ts_epoch,
                rows=excluded.rows,
                updated_at=excluded.updated_at
            """;

    private static final String SELECT_ALL_SQL =
            "SELECT ts_epoch,open,high,low,close,volume FROM candles " +
            "WHERE ticker=? AND timeframe=? ORDER BY ts_epoch ASC";

    private static final String SELECT_RANGE_SQL =
            "SELECT ts_epoch,open,high,low,close,volume FROM candles " +
            "WHERE ticker=? AND timeframe=? AND ts_epoch >= ? AND ts_epoch < ? " +
            "ORDER BY ts_epoch ASC";

    private static final String COUNT_INGEST_SQL =
            "SELECT COUNT(*) FROM ingest_log WHERE ticker=? AND timeframe=?";

    private static final String LAST_TS_SQL =
            "SELECT last_ts_epoch FROM ingest_log WHERE ticker=? AND timeframe=?";

    private final DataSource writeDs;
    private final DataSource readDs;
    private final CandleRowMapper rowMapper = new CandleRowMapper();
    private final JdbcTemplate readJdbc;

    /**
     * Spring-managed constructor — qualifiers route to the correct pools.
     */
    public SqliteCandleRepository(
            @Qualifier("candlesWriteDs") DataSource writeDs,
            @Qualifier("candlesReadDs")  DataSource readDs) {
        this.writeDs  = writeDs;
        this.readDs   = readDs;
        this.readJdbc = new JdbcTemplate(readDs);
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    @Override
    public void upsert(String ticker, TimeFrame tf, List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }

        long maxEpoch = Long.MIN_VALUE;

        try (Connection conn = writeDs.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_CANDLE_SQL)) {
                int batchCount = 0;
                for (Candle c : candles) {
                    long epoch = c.timestamp().toEpochSecond();
                    if (epoch > maxEpoch) maxEpoch = epoch;

                    ps.setString(1, ticker);
                    ps.setString(2, tf.name());
                    ps.setLong(3, epoch);
                    ps.setDouble(4, c.open());
                    ps.setDouble(5, c.high());
                    ps.setDouble(6, c.low());
                    ps.setDouble(7, c.close());
                    ps.setLong(8, c.volume());
                    ps.addBatch();

                    if (++batchCount % BATCH_SIZE == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch(); // flush remaining
            }

            // Update ingest_log
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_INGEST_LOG_SQL)) {
                ps.setString(1, ticker);
                ps.setString(2, tf.name());
                ps.setLong(3, maxEpoch);
                ps.setInt(4, candles.size());
                ps.setLong(5, Instant.now().getEpochSecond());
                ps.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            throw new RepositoryException("upsert() failed for " + ticker + "/" + tf, e);
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    @Override
    public boolean hasLocalData(String ticker, TimeFrame tf) {
        Integer count = readJdbc.queryForObject(COUNT_INGEST_SQL, Integer.class, ticker, tf.name());
        return count != null && count > 0;
    }

    @Override
    public Optional<ZonedDateTime> lastTimestamp(String ticker, TimeFrame tf) {
        List<Long> rows = readJdbc.queryForList(LAST_TS_SQL, Long.class, ticker, tf.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long epoch = rows.get(0);
        return Optional.of(Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC));
    }

    @Override
    public List<Candle> load(String ticker, TimeFrame tf) {
        return readJdbc.query(SELECT_ALL_SQL, rowMapper, ticker, tf.name());
    }

    @Override
    public List<Candle> loadRange(String ticker, TimeFrame tf,
                                  ZonedDateTime from, ZonedDateTime to) {
        return readJdbc.query(SELECT_RANGE_SQL, rowMapper,
                ticker, tf.name(),
                from.toEpochSecond(),
                to.toEpochSecond());
    }

    @Override
    public Stream<Candle> stream(String ticker, TimeFrame tf) {
        try {
            Connection conn = readDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(SELECT_ALL_SQL);
            ps.setFetchSize(1000);
            ps.setString(1, ticker);
            ps.setString(2, tf.name());
            ResultSet rs = ps.executeQuery();

            return StreamSupport
                    .stream(new CandleResultSetSpliterator(rs, rowMapper), false)
                    .onClose(() -> closeQuietly(rs, ps, conn));
        } catch (SQLException e) {
            throw new RepositoryException("stream() failed for " + ticker + "/" + tf, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void closeQuietly(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { rs.close();   } catch (SQLException ignored) {}
        try { ps.close();   } catch (SQLException ignored) {}
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
