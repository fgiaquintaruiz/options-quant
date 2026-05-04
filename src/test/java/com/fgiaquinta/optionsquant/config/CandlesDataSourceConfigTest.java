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
