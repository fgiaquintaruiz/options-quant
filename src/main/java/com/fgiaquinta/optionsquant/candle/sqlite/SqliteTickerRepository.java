package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.RepositoryException;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed repository for {@link TickerInfo} records.
 *
 * <p>Write path uses manual connection management (no {@code @Transactional}) —
 * SQLite WAL mode requires single-writer semantics.
 * Read path uses {@link JdbcTemplate} backed by the read pool.
 *
 * <p>Nullable fields are bound with {@code ps.setObject()} to avoid NPE or
 * silent zero-corruption when the Java value is {@code null}.
 *
 * <p>Only active when {@code candles.store=sqlite} (default) — not present in CSV mode.
 */
@Repository
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class SqliteTickerRepository {

    private static final int BATCH_SIZE = 2000;

    private static final String UPSERT_SQL = """
            INSERT INTO tickers (ticker, company_name, sector, market_cap_billion,
                pe_ratio, dividend_yield, beta, eps_growth, revenue_growth,
                debt_to_equity, roic, notes, active, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT(ticker) DO UPDATE SET
                company_name       = excluded.company_name,
                sector             = excluded.sector,
                market_cap_billion = excluded.market_cap_billion,
                pe_ratio           = excluded.pe_ratio,
                dividend_yield     = excluded.dividend_yield,
                beta               = excluded.beta,
                eps_growth         = excluded.eps_growth,
                revenue_growth     = excluded.revenue_growth,
                debt_to_equity     = excluded.debt_to_equity,
                roic               = excluded.roic,
                notes              = excluded.notes,
                updated_at         = excluded.updated_at
            """;

    private static final String SELECT_ALL_SQL =
            "SELECT ticker, company_name, sector, market_cap_billion, pe_ratio, " +
            "dividend_yield, beta, eps_growth, revenue_growth, debt_to_equity, roic, notes " +
            "FROM tickers";

    private static final String SELECT_BY_SYMBOL_SQL =
            SELECT_ALL_SQL + " WHERE ticker = ?";

    private static final String SELECT_ACTIVE_SQL =
            SELECT_ALL_SQL + " WHERE active = 1";

    private static final String COUNT_ALL_SQL =
            "SELECT COUNT(*) FROM tickers";

    private final DataSource writeDs;
    private final JdbcTemplate readJdbc;
    private final TickerRowMapper rowMapper = new TickerRowMapper();

    public SqliteTickerRepository(
            @Qualifier("candlesWriteDs") DataSource writeDs,
            @Qualifier("candlesReadDs")  DataSource readDs) {
        this.writeDs  = writeDs;
        this.readJdbc = new JdbcTemplate(readDs);
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Inserts or updates a single ticker.
     */
    public void upsert(TickerInfo t) {
        try (Connection conn = writeDs.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                bindUpsertParams(ps, t);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RepositoryException("upsert() failed for ticker " + t.ticker(), e);
        }
    }

    /**
     * Inserts or updates a batch of tickers in a single transaction.
     *
     * @return number of tickers processed (size of the input list)
     */
    public int upsertAll(List<TickerInfo> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }

        try (Connection conn = writeDs.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                int batchCount = 0;
                for (TickerInfo t : list) {
                    bindUpsertParams(ps, t);
                    ps.addBatch();

                    if (++batchCount % BATCH_SIZE == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch(); // flush remaining
            }
            conn.commit();
            return list.size();
        } catch (SQLException e) {
            throw new RepositoryException("upsertAll() failed — batch rolled back", e);
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    public List<TickerInfo> findAll() {
        return readJdbc.query(SELECT_ALL_SQL, rowMapper);
    }

    public Optional<TickerInfo> findBySymbol(String symbol) {
        List<TickerInfo> rows = readJdbc.query(SELECT_BY_SYMBOL_SQL, rowMapper, symbol);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<TickerInfo> findActive() {
        return readJdbc.query(SELECT_ACTIVE_SQL, rowMapper);
    }

    public long countAll() {
        Long count = readJdbc.queryForObject(COUNT_ALL_SQL, Long.class);
        return count != null ? count : 0L;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Binds all 13 parameters for the UPSERT_SQL prepared statement.
     * Nullable Double/Long fields MUST use setObject() — never setDouble(null) (NPE)
     * or setDouble(0.0) (silent data corruption).
     */
    private static void bindUpsertParams(PreparedStatement ps, TickerInfo t) throws SQLException {
        ps.setString(1, t.ticker());
        ps.setString(2, t.companyName());
        ps.setString(3, t.sector());
        ps.setObject(4, t.marketCapBillion());    // Long — nullable
        ps.setObject(5, t.peRatio());             // Double — nullable
        ps.setObject(6, t.dividendYield());       // Double — nullable
        ps.setObject(7, t.beta());                // Double — nullable
        ps.setObject(8, t.epsGrowth());           // Double — nullable
        ps.setObject(9, t.revenueGrowth());       // Double — nullable
        ps.setObject(10, t.debtToEquity());       // Double — nullable
        ps.setObject(11, t.roic());               // Double — nullable
        ps.setString(12, t.notes());
        ps.setLong(13, Instant.now().getEpochSecond());
    }
}
