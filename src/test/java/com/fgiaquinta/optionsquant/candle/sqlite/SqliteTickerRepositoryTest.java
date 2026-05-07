package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.RepositoryException;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SqliteTickerRepository.
 * Uses in-memory SQLite via SqliteTestBase (schema created before each test).
 * The same DataSource is used for both write and read operations.
 */
class SqliteTickerRepositoryTest extends SqliteTestBase {

    private SqliteTickerRepository repo;

    @BeforeEach
    void setUpRepo() {
        repo = new SqliteTickerRepository(ds, ds);
    }

    // -------------------------------------------------------------------------
    // T1 — upsert inserts new row and persists all fields
    // -------------------------------------------------------------------------

    @Test
    void upsert_insertsNewRow_persistsAllFields() {
        TickerInfo ticker = fullTicker("AAPL");

        repo.upsert(ticker);

        Optional<TickerInfo> found = repo.findBySymbol("AAPL");
        assertTrue(found.isPresent(), "Row must be present after upsert");
        TickerInfo stored = found.get();
        assertEquals("AAPL", stored.ticker());
        assertEquals("AAPL Corp", stored.companyName());
        assertEquals("Technology", stored.sector());
        assertEquals(500L, stored.marketCapBillion());
        assertEquals(25.0, stored.peRatio(), 0.0001);
        assertEquals(1.5, stored.dividendYield(), 0.0001);
        assertEquals(1.2, stored.beta(), 0.0001);
        assertEquals(15.0, stored.epsGrowth(), 0.0001);
        assertEquals(12.0, stored.revenueGrowth(), 0.0001);
        assertEquals(0.5, stored.debtToEquity(), 0.0001);
        assertEquals(18.0, stored.roic(), 0.0001);
        assertNull(stored.notes(), "notes must be null when not provided");
    }

    // -------------------------------------------------------------------------
    // T2 — upsert existing ticker updates fields (idempotent)
    // -------------------------------------------------------------------------

    @Test
    void upsert_existingTicker_updatesFields_idempotent() {
        repo.upsert(fullTicker("MSFT"));

        TickerInfo updated = new TickerInfo("MSFT", "Microsoft Updated", "Cloud",
                600L, 30.0, 2.0, 0.9, 20.0, 18.0, 0.3, 22.0, "updated notes");
        repo.upsert(updated);

        Optional<TickerInfo> found = repo.findBySymbol("MSFT");
        assertTrue(found.isPresent());
        TickerInfo stored = found.get();
        assertEquals("Microsoft Updated", stored.companyName());
        assertEquals("Cloud", stored.sector());
        assertEquals(600L, stored.marketCapBillion());
        assertEquals("updated notes", stored.notes());

        // Verify only one row exists (idempotent)
        assertEquals(1L, repo.countAll(), "Must have exactly 1 row after duplicate upsert");
    }

    // -------------------------------------------------------------------------
    // T3 — upsertAll inserts all in single transaction
    // -------------------------------------------------------------------------

    @Test
    void upsertAll_insertsAll_inSingleTransaction() {
        List<TickerInfo> tickers = List.of(
                fullTicker("AAPL"),
                fullTicker("GOOGL"),
                fullTicker("MSFT")
        );

        int count = repo.upsertAll(tickers);

        assertEquals(3, count, "upsertAll must return count of processed rows");
        assertEquals(3L, repo.countAll(), "All 3 tickers must be persisted");
    }

    // -------------------------------------------------------------------------
    // T4 — upsertAll empty list returns zero
    // -------------------------------------------------------------------------

    @Test
    void upsertAll_emptyList_returnsZero() {
        int result = repo.upsertAll(List.of());
        assertEquals(0, result, "upsertAll with empty list must return 0");
        assertEquals(0L, repo.countAll(), "No rows must be inserted for empty list");
    }

    // -------------------------------------------------------------------------
    // T5 — findBySymbol unknown returns empty
    // -------------------------------------------------------------------------

    @Test
    void findBySymbol_unknown_returnsEmpty() {
        Optional<TickerInfo> result = repo.findBySymbol("UNKNOWN");
        assertTrue(result.isEmpty(), "findBySymbol must return empty for unknown ticker");
    }

    // -------------------------------------------------------------------------
    // T6 — findBySymbol existing returns present
    // -------------------------------------------------------------------------

    @Test
    void findBySymbol_existing_returnsPresent() {
        repo.upsert(fullTicker("NVDA"));

        Optional<TickerInfo> result = repo.findBySymbol("NVDA");

        assertTrue(result.isPresent(), "findBySymbol must return present for existing ticker");
        assertEquals("NVDA", result.get().ticker());
    }

    // -------------------------------------------------------------------------
    // T7 — findActive filters inactive rows
    // -------------------------------------------------------------------------

    @Test
    void findActive_filtersInactiveRows() {
        repo.upsert(fullTicker("AAPL"));
        repo.upsert(fullTicker("MSFT"));

        // Deactivate MSFT directly via JDBC (simulating a soft-delete)
        jdbc.update("UPDATE tickers SET active = 0 WHERE ticker = 'MSFT'");

        List<TickerInfo> active = repo.findActive();

        assertEquals(1, active.size(), "findActive must return only active rows");
        assertEquals("AAPL", active.get(0).ticker());
    }

    // -------------------------------------------------------------------------
    // T8 — countAll returns correct count
    // -------------------------------------------------------------------------

    @Test
    void countAll_returnsCorrectCount() {
        assertEquals(0L, repo.countAll(), "countAll must return 0 on empty table");

        repo.upsert(fullTicker("AAPL"));
        assertEquals(1L, repo.countAll());

        repo.upsert(fullTicker("GOOGL"));
        assertEquals(2L, repo.countAll());
    }

    // -------------------------------------------------------------------------
    // T9 — nullable fields stored and retrieved as null
    // -------------------------------------------------------------------------

    @Test
    void nullableFields_storedAndRetrievedAsNull() {
        repo.upsert(minimalTicker("BARE"));

        Optional<TickerInfo> found = repo.findBySymbol("BARE");
        assertTrue(found.isPresent());
        TickerInfo stored = found.get();

        assertEquals("BARE", stored.ticker());
        assertNull(stored.companyName(), "companyName must be null");
        assertNull(stored.sector(), "sector must be null");
        assertNull(stored.marketCapBillion(), "marketCapBillion must be null");
        assertNull(stored.peRatio(), "peRatio must be null");
        assertNull(stored.dividendYield(), "dividendYield must be null");
        assertNull(stored.beta(), "beta must be null");
        assertNull(stored.epsGrowth(), "epsGrowth must be null");
        assertNull(stored.revenueGrowth(), "revenueGrowth must be null");
        assertNull(stored.debtToEquity(), "debtToEquity must be null");
        assertNull(stored.roic(), "roic must be null");
        assertNull(stored.notes(), "notes must be null");
    }

    // -------------------------------------------------------------------------
    // T10 — upsertAll rolls back on SQL error
    // -------------------------------------------------------------------------

    @Test
    void upsertAll_rollbackOnSqlError() {
        // Pre-seed one ticker
        repo.upsert(fullTicker("AAPL"));

        // Create a repository backed by a broken write DataSource that throws on getConnection
        BrokenDataSource brokenDs = new BrokenDataSource(ds);
        SqliteTickerRepository brokenRepo = new SqliteTickerRepository(brokenDs, ds);

        List<TickerInfo> batch = List.of(fullTicker("GOOGL"), fullTicker("MSFT"));

        assertThrows(RepositoryException.class,
                () -> brokenRepo.upsertAll(batch),
                "upsertAll must throw RepositoryException when the connection fails");

        // AAPL still there (pre-seeded), new rows not inserted (rollback occurred)
        assertEquals(1L, repo.countAll(), "Pre-existing rows must be unaffected after rollback");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    static TickerInfo fullTicker(String symbol) {
        return new TickerInfo(symbol, symbol + " Corp", "Technology",
                500L, 25.0, 1.5, 1.2, 15.0, 12.0, 0.5, 18.0, null);
    }

    static TickerInfo minimalTicker(String symbol) {
        return new TickerInfo(symbol, null, null, null, null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // BrokenDataSource — forces getConnection() to throw
    // -------------------------------------------------------------------------

    private static class BrokenDataSource implements javax.sql.DataSource {

        BrokenDataSource(javax.sql.DataSource ignored) {}

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            throw new java.sql.SQLException("Simulated connection failure");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password)
                throws java.sql.SQLException {
            throw new java.sql.SQLException("Simulated connection failure");
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
