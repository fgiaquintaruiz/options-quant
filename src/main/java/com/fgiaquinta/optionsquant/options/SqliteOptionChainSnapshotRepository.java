package com.fgiaquinta.optionsquant.options;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * SQLite-backed implementation of {@link OptionChainSnapshotRepository}.
 *
 * <p>Uses the write DataSource for all inserts; write pool has pool-size=1 to avoid
 * SQLite write contention under concurrent option chain recordings.
 *
 * <p>Active when {@code candles.store=sqlite} (default).
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class SqliteOptionChainSnapshotRepository implements OptionChainSnapshotRepository {

    private static final String INSERT_SQL = """
            INSERT INTO option_chain_snapshot (
                signal_id, ticker, strategy, direction, trigger, expiry,
                strike, right, snapshot_ts,
                bid, ask, underlying_price,
                iv, delta, gamma, theta, vega, opt_price,
                is_paper, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final String SELECT_BY_SIGNAL_ID_SQL = """
            SELECT signal_id, ticker, strategy, direction, trigger, expiry,
                   strike, right, snapshot_ts,
                   bid, ask, underlying_price,
                   iv, delta, gamma, theta, vega, opt_price,
                   is_paper, created_at
            FROM option_chain_snapshot
            WHERE signal_id = ?
            ORDER BY snapshot_ts DESC
            """;

    private static final String SELECT_BY_TICKER_RANGE_SQL = """
            SELECT signal_id, ticker, strategy, direction, trigger, expiry,
                   strike, right, snapshot_ts,
                   bid, ask, underlying_price,
                   iv, delta, gamma, theta, vega, opt_price,
                   is_paper, created_at
            FROM option_chain_snapshot
            WHERE ticker = ? AND snapshot_ts >= ? AND snapshot_ts < ?
            ORDER BY snapshot_ts DESC
            """;

    private final JdbcTemplate writeJdbc;
    private final JdbcTemplate readJdbc;
    private final RowMapper<OptionChainSnapshotRow> rowMapper = new OptionChainRowMapper();

    @Autowired
    public SqliteOptionChainSnapshotRepository(
            @Qualifier("candlesWriteDs") DataSource writeDs,
            @Qualifier("candlesReadDs") DataSource readDs) {
        this.writeJdbc = new JdbcTemplate(writeDs);
        this.readJdbc = new JdbcTemplate(readDs);
    }

    /**
     * Test constructor: uses a single JdbcTemplate for both reads and writes
     * (acceptable for in-memory SQLite tests where pool separation is irrelevant).
     */
    SqliteOptionChainSnapshotRepository(JdbcTemplate jdbc) {
        this.writeJdbc = jdbc;
        this.readJdbc = jdbc;
    }

    @Override
    public void save(OptionChainSnapshotRow row) {
        writeJdbc.update(INSERT_SQL,
                row.signalId(),
                row.ticker(),
                row.strategy(),
                row.direction(),
                row.trigger(),
                row.expiry(),
                row.strike(),
                row.right(),
                row.snapshotTs(),
                row.bid(),
                row.ask(),
                row.underlyingPrice(),
                row.iv(),
                row.delta(),
                row.gamma(),
                row.theta(),
                row.vega(),
                row.optPrice(),
                row.isPaper() ? 1 : 0,
                row.createdAt()
        );
        log.debug("Saved option snapshot: signalId={}, ticker={}, strike={}, right={}",
                row.signalId(), row.ticker(), row.strike(), row.right());
    }

    @Override
    public List<OptionChainSnapshotRow> findBySignalId(String signalId) {
        return readJdbc.query(SELECT_BY_SIGNAL_ID_SQL, rowMapper, signalId);
    }

    @Override
    public List<OptionChainSnapshotRow> findByTicker(String ticker, long fromEpochSeconds, long toEpochSeconds) {
        return readJdbc.query(SELECT_BY_TICKER_RANGE_SQL, rowMapper, ticker, fromEpochSeconds, toEpochSeconds);
    }

    // -------------------------------------------------------------------------
    // RowMapper
    // -------------------------------------------------------------------------

    private static final class OptionChainRowMapper implements RowMapper<OptionChainSnapshotRow> {

        @Override
        public OptionChainSnapshotRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new OptionChainSnapshotRow(
                    rs.getString("signal_id"),
                    rs.getString("ticker"),
                    rs.getString("strategy"),
                    rs.getString("direction"),
                    rs.getString("trigger"),
                    rs.getString("expiry"),
                    rs.getDouble("strike"),
                    rs.getString("right"),
                    rs.getLong("snapshot_ts"),
                    getNullableDouble(rs, "bid"),
                    getNullableDouble(rs, "ask"),
                    getNullableDouble(rs, "underlying_price"),
                    getNullableDouble(rs, "iv"),
                    getNullableDouble(rs, "delta"),
                    getNullableDouble(rs, "gamma"),
                    getNullableDouble(rs, "theta"),
                    getNullableDouble(rs, "vega"),
                    getNullableDouble(rs, "opt_price"),
                    rs.getInt("is_paper") == 1,
                    rs.getLong("created_at")
            );
        }

        private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
            double value = rs.getDouble(column);
            return rs.wasNull() ? null : value;
        }
    }
}
