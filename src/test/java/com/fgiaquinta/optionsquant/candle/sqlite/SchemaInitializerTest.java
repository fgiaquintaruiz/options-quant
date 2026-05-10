package com.fgiaquinta.optionsquant.candle.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SchemaInitializer against a real SQLite instance.
 * Most tests use jdbc:sqlite::memory: for speed.
 * WAL mode test uses a real file via @TempDir (WAL is not supported for :memory: databases).
 */
class SchemaInitializerTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private SchemaInitializer initializer;

    @BeforeEach
    void setUp() {
        dataSource = buildDataSource("jdbc:sqlite::memory:");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        initializer = SchemaInitializer.forTesting(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void afterPropertiesSet_allFourTablesExist() throws Exception {
        initializer.afterPropertiesSet();

        List<String> tables = listTables(dataSource);
        assertTrue(tables.contains("candles"),           "Expected 'candles' table");
        assertTrue(tables.contains("ingest_log"),        "Expected 'ingest_log' table");
        assertTrue(tables.contains("schema_meta"),       "Expected 'schema_meta' table");
        assertTrue(tables.contains("download_progress"), "Expected 'download_progress' table");
    }

    @Test
    void afterPropertiesSet_isIdempotent_noExceptionOnSecondRun() throws Exception {
        initializer.afterPropertiesSet();
        // Must not throw
        assertDoesNotThrow(() -> initializer.afterPropertiesSet(),
            "Second call to afterPropertiesSet must be idempotent (CREATE TABLE IF NOT EXISTS)");
    }

    /**
     * Verifies that the {@code download_progress} table includes the {@code chunk_origin}
     * column with DEFAULT 'HISTORICAL'. This column distinguishes hardcoded-period chunks
     * from dynamic live-tail chunks (DEL1 fix for OUT_OF_RANGE bug).
     */
    @Test
    void downloadProgress_hasChunkOriginColumn_withHistoricalDefault() throws Exception {
        initializer.afterPropertiesSet();

        boolean found = false;
        String defaultValue = null;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(download_progress)")) {
            while (rs.next()) {
                if ("chunk_origin".equals(rs.getString("name"))) {
                    found = true;
                    defaultValue = rs.getString("dflt_value");
                    break;
                }
            }
        }
        assertTrue(found, "Expected 'chunk_origin' column in download_progress");
        assertNotNull(defaultValue, "chunk_origin must have a non-null DEFAULT");
        // SQLite stores defaults including the surrounding quotes for TEXT
        assertTrue(defaultValue.replace("'", "").equals("HISTORICAL"),
            "Expected DEFAULT 'HISTORICAL' for chunk_origin, got: " + defaultValue);

        // Insert a row WITHOUT chunk_origin to confirm DEFAULT is applied at write time
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO download_progress (ticker, timeframe, last_chunk_end_ts, status, updated_at) " +
                "VALUES ('AAPL', 'DAY_1', 0, 'COMPLETE_YFINANCE', 0)");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT chunk_origin FROM download_progress WHERE ticker='AAPL' AND timeframe='DAY_1'")) {
                assertTrue(rs.next(), "Inserted row should be readable");
                assertEquals("HISTORICAL", rs.getString("chunk_origin"),
                    "DEFAULT 'HISTORICAL' must apply when chunk_origin is omitted on INSERT");
            }
        }
    }

    /**
     * WAL mode cannot be enabled on :memory: databases — SQLite always reports "memory" for them.
     * This test uses a real file to verify WAL activation works as expected on the write pool.
     */
    @Test
    void walModeIsActive_onFileDatabase_afterInit() throws Exception {
        String dbPath = tempDir.resolve("wal-test.db").toAbsolutePath().toString();
        try (HikariDataSource fileDs = buildWalDataSource(dbPath)) {
            JdbcTemplate jdbc = new JdbcTemplate(fileDs);
            SchemaInitializer fileInitializer = SchemaInitializer.forTesting(jdbc);
            fileInitializer.afterPropertiesSet();

            try (Connection conn = fileDs.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {

                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1),
                    "Expected WAL journal mode to be active on file-based SQLite database");
            }
        }
    }

    @Test
    void index_idx_candles_tf_ts_exists_afterInit() throws Exception {
        initializer.afterPropertiesSet();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_candles_tf_ts'")) {

            assertTrue(rs.next(), "Expected index 'idx_candles_tf_ts' to exist in sqlite_master");
            assertEquals("idx_candles_tf_ts", rs.getString("name"));
        }
    }

    /**
     * Verifies that SchemaInitializer retries up to 3 times when a DDL statement throws
     * SQLITE_BUSY (error code 5), and succeeds on the third attempt.
     *
     * <p>Note: real SQLite locking cannot be reproduced with jdbc:sqlite::memory: —
     * the retry is tested via a spy JdbcTemplate that throws on the first two invocations
     * and delegates to the real template on the third.
     */
    @Test
    void afterPropertiesSet_retriesDdl_whenSqliteBusyOnFirstTwoAttempts() {
        AtomicInteger callCount = new AtomicInteger(0);

        // Real in-memory template for successful execution
        JdbcTemplate realJdbc = new JdbcTemplate(dataSource);

        // Spy: fail first two calls with SQLITE_BUSY (error code 5), succeed on the third
        JdbcTemplate spyJdbc = new JdbcTemplate(dataSource) {
            @Override
            public void execute(String sql) {
                int attempt = callCount.incrementAndGet();
                if (attempt <= 2) {
                    // SQLite BUSY error code is 5 — wrap in a Spring UncategorizedSQLException
                    // the same way Spring's JdbcTemplate would surface it from the JDBC driver
                    throw new org.springframework.jdbc.UncategorizedSQLException(
                        "StatementCallback", sql,
                        new java.sql.SQLException(
                            "The database file is locked (database is locked)",
                            "SQLITE_BUSY", 5));
                }
                realJdbc.execute(sql);
            }
        };

        SchemaInitializer retryInitializer = SchemaInitializer.forTesting(spyJdbc);

        // Must succeed after retries — must NOT throw
        assertDoesNotThrow(() -> retryInitializer.afterPropertiesSet(),
            "SchemaInitializer must succeed after retrying on SQLITE_BUSY");

        // First DDL (createCandlesTable) must have been attempted at least 3 times before success
        assertTrue(callCount.get() >= 3,
            "Expected at least 3 execute() calls (2 failures + 1 success), got: " + callCount.get());
    }

    // -------------------------------------------------------------------------
    // skip_error_code column tests (Phase 1 — SKIPPED_PERMANENT migration)
    // -------------------------------------------------------------------------

    /**
     * Tests that afterPropertiesSet() adds skip_error_code column when it is missing
     * (simulates an existing database that was created before the column was added).
     */
    @Test
    void whenColumnMissing_alterTableApplied() throws Exception {
        // Create a DB with download_progress WITHOUT skip_error_code (old schema)
        HikariDataSource oldDs = buildDataSource("jdbc:sqlite::memory:");
        JdbcTemplate oldJdbc = new JdbcTemplate(oldDs);
        // Create the table manually without skip_error_code to simulate a pre-migration DB
        oldJdbc.execute("""
            CREATE TABLE IF NOT EXISTS download_progress (
                ticker            TEXT    NOT NULL,
                timeframe         TEXT    NOT NULL,
                last_chunk_end_ts INTEGER NOT NULL,
                status            TEXT    NOT NULL,
                updated_at        INTEGER NOT NULL,
                chunk_origin      TEXT    NOT NULL DEFAULT 'HISTORICAL',
                PRIMARY KEY (ticker, timeframe)
            ) WITHOUT ROWID
            """);
        // Also create the other tables so afterPropertiesSet doesn't fail on them
        oldJdbc.execute("CREATE TABLE IF NOT EXISTS candles (ticker TEXT NOT NULL, timeframe TEXT NOT NULL, ts_epoch INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume INTEGER NOT NULL, PRIMARY KEY (ticker, timeframe, ts_epoch)) WITHOUT ROWID");
        oldJdbc.execute("CREATE INDEX IF NOT EXISTS idx_candles_tf_ts ON candles(ticker, timeframe, ts_epoch DESC)");
        oldJdbc.execute("CREATE TABLE IF NOT EXISTS ingest_log (ticker TEXT NOT NULL, timeframe TEXT NOT NULL, last_ts_epoch INTEGER NOT NULL, rows INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (ticker, timeframe)) WITHOUT ROWID");
        oldJdbc.execute("CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        oldJdbc.execute("CREATE TABLE IF NOT EXISTS tickers (ticker TEXT PRIMARY KEY, company_name TEXT, sector TEXT, market_cap_billion INTEGER, pe_ratio REAL, dividend_yield REAL, beta REAL, eps_growth REAL, revenue_growth REAL, debt_to_equity REAL, roic REAL, notes TEXT, active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL) WITHOUT ROWID");
        oldJdbc.execute("CREATE INDEX IF NOT EXISTS idx_tickers_active ON tickers(active)");
        oldJdbc.execute("CREATE INDEX IF NOT EXISTS idx_tickers_sector ON tickers(sector)");

        SchemaInitializer migrationInitializer = SchemaInitializer.forTesting(oldJdbc);

        // Run afterPropertiesSet — this should run ALTER TABLE to add skip_error_code
        assertDoesNotThrow(() -> migrationInitializer.afterPropertiesSet());

        // Verify skip_error_code column now exists
        boolean found = false;
        try (Connection conn = oldDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(download_progress)")) {
            while (rs.next()) {
                if ("skip_error_code".equals(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "skip_error_code column must exist after afterPropertiesSet() migration");
        oldDs.close();
    }

    /**
     * Tests that afterPropertiesSet() does NOT throw when skip_error_code already exists
     * (idempotent migration — running twice is safe).
     */
    @Test
    void whenColumnAlreadyPresent_alterTableSkippedSilently() throws Exception {
        // Run afterPropertiesSet once (creates the column)
        initializer.afterPropertiesSet();

        // Run again — the ALTER TABLE should be swallowed silently
        assertDoesNotThrow(() -> initializer.afterPropertiesSet(),
            "Second afterPropertiesSet() must not throw when skip_error_code already exists");

        // Column must still be present
        boolean found = false;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(download_progress)")) {
            while (rs.next()) {
                if ("skip_error_code".equals(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "skip_error_code column must still be present after idempotent migration");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static HikariDataSource buildDataSource(String jdbcUrl) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setMaximumPoolSize(1);
        // Note: journal_mode=WAL has no effect on :memory: — kept for API completeness
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000;");
        return new HikariDataSource(cfg);
    }

    private static HikariDataSource buildWalDataSource(String dbPath) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath);
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql(
            "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");
        return new HikariDataSource(cfg);
    }

    private static List<String> listTables(DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table'")) {

            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
            return names;
        }
    }
}
