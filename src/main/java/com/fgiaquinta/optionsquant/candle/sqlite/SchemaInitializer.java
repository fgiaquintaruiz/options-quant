package com.fgiaquinta.optionsquant.candle.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

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
    static SchemaInitializer forTesting(JdbcTemplate jdbc) {
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
        log.info("SQLite candle schema ready.");
    }

    // -------------------------------------------------------------------------
    // DDL
    // -------------------------------------------------------------------------

    private void createCandlesTable() {
        jdbc.execute("""
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
            """);
    }

    private void createCandlesIndex() {
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_candles_tf_ts
                ON candles(ticker, timeframe, ts_epoch DESC)
            """);
    }

    private void createIngestLogTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ingest_log (
                ticker        TEXT    NOT NULL,
                timeframe     TEXT    NOT NULL,
                last_ts_epoch INTEGER NOT NULL,
                rows          INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                PRIMARY KEY (ticker, timeframe)
            ) WITHOUT ROWID
            """);
    }

    private void createSchemaMetaTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS schema_meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """);
    }

    private void createDownloadProgressTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS download_progress (
                ticker            TEXT    NOT NULL,
                timeframe         TEXT    NOT NULL,
                last_chunk_end_ts INTEGER NOT NULL,
                status            TEXT    NOT NULL,
                updated_at        INTEGER NOT NULL,
                PRIMARY KEY (ticker, timeframe)
            ) WITHOUT ROWID
            """);
    }
}
