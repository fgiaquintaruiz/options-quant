package com.fgiaquinta.optionsquant.options;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Creates the {@code option_chain_snapshot} table idempotently on startup.
 *
 * <p>All DDL uses {@code IF NOT EXISTS} so repeated calls are safe.
 * Active when {@code candles.store=sqlite} (default) — same condition as
 * {@link com.fgiaquinta.optionsquant.candle.sqlite.SchemaInitializer}, so the two inits
 * always run together.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class OptionChainSchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Autowired
    public OptionChainSchemaInitializer(@Qualifier("candlesWriteDs") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private OptionChainSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Test factory: creates an initializer directly from a pre-built JdbcTemplate,
     * bypassing Spring DI and bean qualification.
     */
    public static OptionChainSchemaInitializer forTesting(JdbcTemplate jdbc) {
        return new OptionChainSchemaInitializer(jdbc);
    }

    @Override
    public void afterPropertiesSet() {
        log.info("Initializing option_chain_snapshot schema...");
        createOptionChainSnapshotTable();
        log.info("option_chain_snapshot schema ready.");
    }

    /**
     * Creates the {@code option_chain_snapshot} table and its indexes.
     * Safe to call multiple times — uses {@code IF NOT EXISTS} throughout.
     */
    public void createOptionChainSnapshotTable() {
        executeWithRetry("""
                CREATE TABLE IF NOT EXISTS option_chain_snapshot (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    signal_id TEXT NOT NULL,
                    ticker TEXT NOT NULL,
                    strategy TEXT,
                    direction TEXT NOT NULL,
                    trigger TEXT NOT NULL,
                    expiry TEXT NOT NULL,
                    strike REAL NOT NULL,
                    right TEXT NOT NULL,
                    snapshot_ts INTEGER NOT NULL,
                    bid REAL,
                    ask REAL,
                    underlying_price REAL,
                    iv REAL,
                    delta REAL,
                    gamma REAL,
                    theta REAL,
                    vega REAL,
                    opt_price REAL,
                    is_paper INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL
                ) STRICT
                """, 3, 500);

        executeWithRetry("""
                CREATE INDEX IF NOT EXISTS idx_ocs_ticker_ts
                    ON option_chain_snapshot(ticker, snapshot_ts DESC)
                """, 3, 500);

        executeWithRetry("""
                CREATE INDEX IF NOT EXISTS idx_ocs_signal_id
                    ON option_chain_snapshot(signal_id)
                """, 3, 500);
    }

    // -------------------------------------------------------------------------
    // DDL helpers
    // -------------------------------------------------------------------------

    /**
     * Executes a DDL statement with retry on SQLITE_BUSY (error code 5).
     * Same implementation pattern as SchemaInitializer.
     */
    private void executeWithRetry(String sql, int maxAttempts, long backoffMs) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                jdbc.execute(sql);
                return;
            } catch (UncategorizedSQLException ex) {
                SQLException cause = ex.getSQLException();
                boolean isBusy = cause != null && cause.getErrorCode() == 5;
                if (!isBusy || attempt >= maxAttempts) {
                    throw ex;
                }
                log.warn("SQLITE_BUSY on DDL (attempt {}/{}), retrying in {}ms", attempt, maxAttempts, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}
