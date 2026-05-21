package com.fgiaquinta.optionsquant.options;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests OptionChainSnapshotRepository against an in-memory SQLite instance.
 * Schema is bootstrapped by OptionChainSchemaInitializer (same pattern as SchemaInitializerTest).
 */
class OptionChainSnapshotRepositoryTest {

    private HikariDataSource dataSource;
    private OptionChainSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite::memory:");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000;");
        dataSource = new HikariDataSource(cfg);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OptionChainSchemaInitializer schemaInit = OptionChainSchemaInitializer.forTesting(jdbc);
        schemaInit.createOptionChainSnapshotTable();

        repository = new SqliteOptionChainSnapshotRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ─── save + findBySignalId ────────────────────────────────────────────────

    @Test
    @DisplayName("save persists a row and findBySignalId returns it")
    void save_persistsRow_and_findBySignalId_returnsIt() {
        long now = Instant.now().getEpochSecond();
        OptionChainSnapshotRow row = new OptionChainSnapshotRow(
                "sig-001",
                "NVDA",
                "p1_squeeze_put",
                "PUT",
                "SIGNAL",
                "20260620",
                900.0,
                "P",
                now,
                1.50, 1.55,
                895.0,
                0.45, -0.35, 0.002, -0.08, 0.25, 1.52,
                true,
                now
        );

        repository.save(row);

        List<OptionChainSnapshotRow> found = repository.findBySignalId("sig-001");

        assertThat(found).hasSize(1);
        OptionChainSnapshotRow saved = found.get(0);
        assertThat(saved.signalId()).isEqualTo("sig-001");
        assertThat(saved.ticker()).isEqualTo("NVDA");
        assertThat(saved.strategy()).isEqualTo("p1_squeeze_put");
        assertThat(saved.direction()).isEqualTo("PUT");
        assertThat(saved.trigger()).isEqualTo("SIGNAL");
        assertThat(saved.expiry()).isEqualTo("20260620");
        assertThat(saved.strike()).isEqualTo(900.0);
        assertThat(saved.right()).isEqualTo("P");
        assertThat(saved.bid()).isEqualTo(1.50);
        assertThat(saved.ask()).isEqualTo(1.55);
        assertThat(saved.underlyingPrice()).isEqualTo(895.0);
        assertThat(saved.iv()).isEqualTo(0.45);
        assertThat(saved.delta()).isEqualTo(-0.35);
        assertThat(saved.isPaper()).isTrue();
    }

    @Test
    @DisplayName("findBySignalId returns empty list when no matching signalId")
    void findBySignalId_returnsEmpty_whenNoMatch() {
        List<OptionChainSnapshotRow> found = repository.findBySignalId("nonexistent-signal");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findBySignalId returns all rows for the same signalId (11 strikes)")
    void findBySignalId_returnsAllRowsForSignal() {
        long now = Instant.now().getEpochSecond();
        for (int i = 0; i < 11; i++) {
            double strike = 890.0 + (i * 5.0);
            OptionChainSnapshotRow row = new OptionChainSnapshotRow(
                    "batch-sig-1",
                    "NVDA", null, "CALL", "SCHEDULED",
                    "20260620", strike, "C",
                    now,
                    0.80 + i * 0.05, 0.85 + i * 0.05,
                    895.0,
                    0.40, 0.30 + i * 0.02, 0.001, -0.05, 0.20, 0.82 + i * 0.05,
                    false, now
            );
            repository.save(row);
        }

        List<OptionChainSnapshotRow> found = repository.findBySignalId("batch-sig-1");
        assertThat(found).hasSize(11);
    }

    // ─── findByTicker with time range ────────────────────────────────────────

    @Test
    @DisplayName("findByTicker returns rows in the requested time range")
    void findByTicker_returnsRowsInTimeRange() {
        long base = Instant.now().getEpochSecond();
        long t1 = base - 3600;  // 1 hour ago
        long t2 = base - 1800;  // 30 min ago
        long t3 = base - 60;    // 1 min ago

        OptionChainSnapshotRow r1 = buildRow("AAPL", "sig-a", t1, 185.0, base);
        OptionChainSnapshotRow r2 = buildRow("AAPL", "sig-b", t2, 185.0, base);
        OptionChainSnapshotRow r3 = buildRow("AAPL", "sig-c", t3, 185.0, base);
        OptionChainSnapshotRow other = buildRow("TSLA", "sig-d", t2, 300.0, base);

        repository.save(r1);
        repository.save(r2);
        repository.save(r3);
        repository.save(other);

        // Window: t1 inclusive to t3 exclusive
        List<OptionChainSnapshotRow> found = repository.findByTicker("AAPL", t1, t3);

        assertThat(found).hasSize(2);
        assertThat(found).allMatch(r -> r.ticker().equals("AAPL"));
        // Results ordered by snapshot_ts DESC
        assertThat(found.get(0).snapshotTs()).isEqualTo(t2);
        assertThat(found.get(1).snapshotTs()).isEqualTo(t1);
    }

    @Test
    @DisplayName("findByTicker returns empty when no rows in the time window")
    void findByTicker_returnsEmpty_whenOutsideWindow() {
        long now = Instant.now().getEpochSecond();
        OptionChainSnapshotRow row = buildRow("SPY", "sig-x", now - 7200, 500.0, now);
        repository.save(row);

        // Query window does not include the saved row
        List<OptionChainSnapshotRow> found = repository.findByTicker("SPY", now - 3600, now);
        assertThat(found).isEmpty();
    }

    // ─── null fields are preserved round-trip ────────────────────────────────

    @Test
    @DisplayName("nullable fields (strategy, greeks) are stored and retrieved as null")
    void nullableFields_roundTrip() {
        long now = Instant.now().getEpochSecond();
        OptionChainSnapshotRow row = new OptionChainSnapshotRow(
                "sig-null", "GLD", null, "CALL", "SCHEDULED",
                "20260718", 200.0, "C",
                now,
                null, null, null,
                null, null, null, null, null, null,
                true, now
        );

        repository.save(row);
        List<OptionChainSnapshotRow> found = repository.findBySignalId("sig-null");

        assertThat(found).hasSize(1);
        OptionChainSnapshotRow saved = found.get(0);
        assertThat(saved.strategy()).isNull();
        assertThat(saved.bid()).isNull();
        assertThat(saved.ask()).isNull();
        assertThat(saved.iv()).isNull();
        assertThat(saved.delta()).isNull();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static OptionChainSnapshotRow buildRow(
            String ticker, String signalId, long snapshotTs, double strike, long createdAt) {
        return new OptionChainSnapshotRow(
                signalId, ticker, null, "CALL", "SCHEDULED",
                "20260620", strike, "C",
                snapshotTs,
                1.0, 1.05, strike + 5.0,
                0.30, 0.40, 0.001, -0.04, 0.18, 1.02,
                true, createdAt
        );
    }
}
