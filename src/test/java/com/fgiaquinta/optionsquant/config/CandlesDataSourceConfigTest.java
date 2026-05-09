package com.fgiaquinta.optionsquant.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CandlesDataSourceConfig.
 * Uses jdbc:sqlite::memory: and TempDir to avoid touching the filesystem unexpectedly.
 */
class CandlesDataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void writePool_createsConnection_successfully() throws Exception {
        CandlesDataSourceConfig config = new CandlesDataSourceConfig();
        String dbPath = tempDir.resolve("test-write.db").toAbsolutePath().toString();

        DataSource ds = config.candlesWriteDataSource(dbPath);
        assertNotNull(ds, "Write DataSource must not be null");

        try (Connection conn = ds.getConnection()) {
            assertFalse(conn.isClosed(), "Connection must be open");
        }

        if (ds instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    @Test
    void writePool_hasWalModeActive_afterConnection() throws Exception {
        CandlesDataSourceConfig config = new CandlesDataSourceConfig();
        String dbPath = tempDir.resolve("test-wal.db").toAbsolutePath().toString();

        DataSource ds = config.candlesWriteDataSource(dbPath);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {

            assertTrue(rs.next(), "Expected result from PRAGMA journal_mode");
            String journalMode = rs.getString(1);
            assertEquals("wal", journalMode,
                "Expected WAL journal mode to be active after write pool connection init");
        }

        if (ds instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    /**
     * Verifies the write pool is configured with maximumPoolSize=1 (SQLite only allows one
     * concurrent writer) and that busy_timeout is embedded in the JDBC URL so it is applied
     * at connection-open time — before any PRAGMA can run. This prevents SQLITE_BUSY during
     * concurrent pool initialization at startup.
     */
    @Test
    void writePool_hasSingleConnectionAndBusyTimeoutInUrl() throws Exception {
        CandlesDataSourceConfig config = new CandlesDataSourceConfig();
        String dbPath = tempDir.resolve("test-pool-config.db").toAbsolutePath().toString();

        DataSource ds = config.candlesWriteDataSource(dbPath);
        assertNotNull(ds);

        HikariDataSource hds = (HikariDataSource) ds;
        try {
            assertEquals(1, hds.getMaximumPoolSize(),
                "Write pool must have maximumPoolSize=1 — SQLite only allows one concurrent writer");
            assertTrue(hds.getJdbcUrl().contains("busy_timeout=30000"),
                "Write pool JDBC URL must contain busy_timeout=30000 so the timeout is applied " +
                "at connection-open time, before any PRAGMA runs");
        } finally {
            hds.close();
        }
    }

    /**
     * Verifies the read pool JDBC URL also carries busy_timeout so WAL-mode reader connections
     * that overlap with schema init don't fail immediately with SQLITE_BUSY.
     */
    @Test
    void readPool_hasBusyTimeoutInUrl() throws Exception {
        CandlesDataSourceConfig config = new CandlesDataSourceConfig();
        String dbPath = tempDir.resolve("test-read-url.db").toAbsolutePath().toString();

        // Create the DB file first via write pool
        DataSource writeDs = config.candlesWriteDataSource(dbPath);
        if (writeDs instanceof HikariDataSource w) {
            try (Connection c = w.getConnection()) { /* just open so the file is created */ }
            w.close();
        }

        DataSource ds = config.candlesReadDataSource(dbPath);
        HikariDataSource hds = (HikariDataSource) ds;
        try {
            assertTrue(hds.getJdbcUrl().contains("busy_timeout=30000"),
                "Read pool JDBC URL must contain busy_timeout=30000 so reader connections " +
                "that race with write-pool startup don't fail immediately with SQLITE_BUSY");
        } finally {
            hds.close();
        }
    }

    @Test
    void readPool_createsConnection_successfully() throws Exception {
        CandlesDataSourceConfig config = new CandlesDataSourceConfig();
        // Create the file first using write pool, then open read pool
        String dbPath = tempDir.resolve("test-read.db").toAbsolutePath().toString();

        // Ensure file exists (write pool creates it)
        DataSource writeDs = config.candlesWriteDataSource(dbPath);
        try (Connection c = writeDs.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS ping (id INTEGER)");
        }
        if (writeDs instanceof HikariDataSource hds) {
            hds.close();
        }

        DataSource readDs = config.candlesReadDataSource(dbPath);
        assertNotNull(readDs, "Read DataSource must not be null");

        try (Connection conn = readDs.getConnection()) {
            assertFalse(conn.isClosed(), "Read connection must be open");
        }

        if (readDs instanceof HikariDataSource hds) {
            hds.close();
        }
    }
}
