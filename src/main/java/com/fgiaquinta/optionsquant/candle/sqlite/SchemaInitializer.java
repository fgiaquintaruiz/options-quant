package com.fgiaquinta.optionsquant.candle.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Creates the SQLite schema idempotently on startup.
 *
 * <p>All DDL uses {@code IF NOT EXISTS} so repeated calls are safe.
 * This bean is only active when {@code candles.store=sqlite} (default).
 */
@Component
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class SchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    /**
     * Spring-managed constructor: wires in the write DataSource via qualifier.
     * For direct instantiation in tests, use the static factory
     * {@link #forTesting(JdbcTemplate)}.
     */
    @Autowired
    public SchemaInitializer(@Qualifier("candlesWriteDs") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Test factory: creates an initializer directly from a pre-built JdbcTemplate,
     * bypassing Spring DI and bean qualification.
     */
    public static SchemaInitializer forTesting(JdbcTemplate jdbc) {
        return new SchemaInitializer(jdbc);
    }

    @Override
    public void afterPropertiesSet() {
        log.info("Initializing SQLite candle schema...");
        createCandlesTable();
        createCandlesIndex();
        createIngestLogTable();
        createSchemaMetaTable();
        createDownloadProgressTable();
        createTickersTable();
        createTickersIndexes();
        log.info("SQLite candle schema ready.");
    }

    // -------------------------------------------------------------------------
    // DDL
    // -------------------------------------------------------------------------

    /**
     * Executes a DDL statement with up to {@code maxAttempts} retries on SQLITE_BUSY (error code 5).
     *
     * <p>SQLITE_BUSY at schema-init time means another OS-level process holds the file lock —
     * {@code busy_timeout} only helps for <em>within-process</em> contention, not cross-process.
     * Retrying here lets the lock clear before aborting startup entirely.
     *
     * <p>This retry is intentionally scoped to DDL at startup. Regular queries must NOT use it.
     *
     * @param sql         the DDL statement to execute
     * @param maxAttempts maximum total attempts (including the first); must be &ge; 1
     * @param backoffMs   milliseconds to wait between attempts
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
                boolean isBusy = cause != null && cause.getErrorCode() == 5; // SQLITE_BUSY
                if (!isBusy || attempt >= maxAttempts) {
                    throw ex;
                }
                log.warn("SQLITE_BUSY on DDL (attempt {}/{}), retrying in {}ms — sql preview: {}",
                    attempt, maxAttempts, backoffMs, sql.strip().lines().findFirst().orElse(""));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private void createCandlesTable() {
        executeWithRetry("""
            CREATE TABLE IF NOT EXISTS candles (
                ticker      TEXT    NOT NULL,
                timeframe   TEXT    NOT NULL,
                ts_epoch    INTEGER NOT NULL,
                open        REAL    NOT NULL,
                high        REAL    NOT NULL,
                low         REAL    NOT NULL,
                close       REAL    NOT NULL,
                volume      INTEGER NOT NULL,
                PRIMARY KEY (ticker, timeframe, ts_epoch)
            ) WITHOUT ROWID
            """, 3, 500);
    }

    private void createCandlesIndex() {
        executeWithRetry("""
            CREATE INDEX IF NOT EXISTS idx_candles_tf_ts
                ON candles(ticker, timeframe, ts_epoch DESC)
            """, 3, 500);
    }

    private void createIngestLogTable() {
        executeWithRetry("""
            CREATE TABLE IF NOT EXISTS ingest_log (
                ticker        TEXT    NOT NULL,
                timeframe     TEXT    NOT NULL,
                last_ts_epoch INTEGER NOT NULL,
                rows          INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                PRIMARY KEY (ticker, timeframe)
            ) WITHOUT ROWID
            """, 3, 500);
    }

    private void createSchemaMetaTable() {
        executeWithRetry("""
            CREATE TABLE IF NOT EXISTS schema_meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """, 3, 500);
    }

    private void createDownloadProgressTable() {
        executeWithRetry("""
            CREATE TABLE IF NOT EXISTS download_progress (
                ticker            TEXT    NOT NULL,
                timeframe         TEXT    NOT NULL,
                last_chunk_end_ts INTEGER NOT NULL,
                status            TEXT    NOT NULL,
                updated_at        INTEGER NOT NULL,
                chunk_origin      TEXT    NOT NULL DEFAULT 'HISTORICAL',
                PRIMARY KEY (ticker, timeframe)
            ) WITHOUT ROWID
            """, 3, 500);

        // Idempotent migration for pre-existing databases that lack the column.
        // SQLite has no IF NOT EXISTS for ADD COLUMN; we swallow the duplicate-column error.
        try {
            jdbc.execute(
                "ALTER TABLE download_progress ADD COLUMN chunk_origin TEXT NOT NULL DEFAULT 'HISTORICAL'");
            log.info("Added 'chunk_origin' column to existing download_progress table.");
        } catch (DataAccessException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
            if (msg.contains("duplicate column name") || msg.contains("already exists")) {
                log.debug("download_progress.chunk_origin already present — skipping ALTER.");
            } else {
                throw ex;
            }
        }
    }

    private void createTickersTable() {
        executeWithRetry("""
            CREATE TABLE IF NOT EXISTS tickers (
                ticker              TEXT PRIMARY KEY,
                company_name        TEXT,
                sector              TEXT,
                market_cap_billion  INTEGER,
                pe_ratio            REAL,
                dividend_yield      REAL,
                beta                REAL,
                eps_growth          REAL,
                revenue_growth      REAL,
                debt_to_equity      REAL,
                roic                REAL,
                notes               TEXT,
                active              INTEGER NOT NULL DEFAULT 1,
                updated_at          INTEGER NOT NULL
            ) WITHOUT ROWID
            """, 3, 500);
    }

    private void createTickersIndexes() {
        executeWithRetry("CREATE INDEX IF NOT EXISTS idx_tickers_active ON tickers(active)", 3, 500);
        executeWithRetry("CREATE INDEX IF NOT EXISTS idx_tickers_sector ON tickers(sector)", 3, 500);
    }
}
