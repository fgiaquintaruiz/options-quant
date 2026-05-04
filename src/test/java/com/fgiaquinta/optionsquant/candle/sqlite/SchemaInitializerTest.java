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
