package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.sqlite.SchemaInitializer;
import com.fgiaquinta.optionsquant.candle.sqlite.SqliteTickerRepository;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 TDD tests — TickerService cold/warm start via SQLite.
 *
 * <p>Setup: in-memory SQLite (one pool, single connection for write+read),
 * schema created by {@link SchemaInitializer#forTesting}.
 * TickerService is constructed directly — no Spring context.
 */
class TickerServiceSqliteTest {

    @TempDir
    Path tempDir;

    private DataSource ds;
    private SqliteTickerRepository sqliteRepo;
    private IbkrProperties ibkrProperties;
    private TickerRuntimeConfigStore runtimeConfigStore;

    @BeforeEach
    void setUp() {
        // In-memory SQLite — pool size 1, same DS for read and write
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite::memory:");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000;");
        ds = new HikariDataSource(cfg);

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaInitializer.forTesting(jdbc).afterPropertiesSet();

        sqliteRepo = new SqliteTickerRepository(ds, ds);
        ibkrProperties = mock(IbkrProperties.class);
        runtimeConfigStore = mock(TickerRuntimeConfigStore.class);
        when(ibkrProperties.hotTickerCount()).thenReturn(20);
    }

    @AfterEach
    void tearDown() {
        if (ds instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T1 — cold start: empty DB → loads from CSV+JSON → populates SQLite
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void firstBoot_emptyDb_loadsFromCsvJson_thenPopulatesSqlite() throws IOException {
        // Given: a minimal tickers.csv in a temp dir
        Path csvFile = writeCsv(tempDir);
        // no ticker-runtime.json → runtimeConfigStore returns empty
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());

        TickerService service = buildService(csvFile.toString());

        // When
        service.loadTickers();

        // Then
        assertThat(service.getTickerSymbols()).isNotEmpty();
        assertThat(sqliteRepo.countAll()).isGreaterThan(0);
        assertThat(service.isLoaded()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T2 — warm start: DB has rows → loads from SQLite, CSV not needed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void warmBoot_dbHasRows_loadsFromSqlite_skipsCsv() {
        // Given: DB pre-populated (CSV file does NOT need to exist)
        List<TickerInfo> seed = List.of(
                ticker("AAPL", "Apple Inc", "Technology"),
                ticker("MSFT", "Microsoft Corp", "Technology")
        );
        sqliteRepo.upsertAll(seed);
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());

        // CSV path points to a non-existent file — must NOT be used in warm start
        TickerService service = buildService(tempDir.resolve("nonexistent.csv").toString());

        // When
        service.loadTickers();

        // Then
        assertThat(service.getTickerSymbols()).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(service.isLoaded()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T3 — reload refreshes SQLite and in-memory map
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reload_refreshesSqlite_andInMemoryMap() throws IOException {
        // Given: service already loaded from CSV
        Path csvFile = writeCsv(tempDir);
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());
        TickerService service = buildService(csvFile.toString());
        service.loadTickers();
        long countAfterFirstLoad = sqliteRepo.countAll();

        // When: reload (simulates ticker-runtime.json update)
        service.reload();

        // Then: SQLite is refreshed (count >= same or more, service still loaded)
        assertThat(service.isLoaded()).isTrue();
        assertThat(sqliteRepo.countAll()).isGreaterThanOrEqualTo(countAfterFirstLoad);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T4 — getTickerSymbols returns at least 1 symbol after load
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getTickerSymbols_afterLoad_returnsExpectedSize() throws IOException {
        Path csvFile = writeCsv(tempDir);
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());
        TickerService service = buildService(csvFile.toString());

        service.loadTickers();

        assertThat(service.getTickerSymbols().size()).isGreaterThanOrEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T5 — getTickerInfo unknown returns empty Optional (public API preserved)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getTickerInfo_unknown_returnsEmpty_publicApiPreserved() throws IOException {
        Path csvFile = writeCsv(tempDir);
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());
        TickerService service = buildService(csvFile.toString());
        service.loadTickers();

        Optional<TickerInfo> result = service.getTickerInfo("UNKNOWN_XYZ");

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T6 — getHotTickers still sourced from JSON (runtime config wins)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_stillSourcedFromJson() {
        // DB pre-populated so warm start
        List<TickerInfo> seed = List.of(
                ticker("NVDA", "NVIDIA Corp", "Technology"),
                ticker("MSFT", "Microsoft Corp", "Technology"),
                ticker("AAPL", "Apple Inc", "Technology")
        );
        sqliteRepo.upsertAll(seed);

        com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload runtime =
                new com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload(
                        List.of("NVDA", "MSFT", "AAPL"),
                        List.of("NVDA", "MSFT"),   // hot list from JSON
                        java.util.Map.of(),
                        null
                );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        TickerService service = buildService(tempDir.resolve("irrelevant.csv").toString());
        service.loadTickers();

        List<String> hot = service.getHotTickers();

        assertThat(hot).isNotEmpty();
        assertThat(hot).contains("NVDA");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TickerService buildService(String csvPath) {
        return new TickerService(ibkrProperties, runtimeConfigStore, sqliteRepo, csvPath);
    }

    /**
     * Writes a minimal tickers.csv (header + 2 rows) to the given directory.
     */
    private Path writeCsv(Path dir) throws IOException {
        Path file = dir.resolve("tickers.csv");
        Files.writeString(file,
                "ticker,company_name,sector,market_cap,pe_ratio,dividend_yield,beta,eps_growth,revenue_growth,debt_to_equity,roic,notes\n" +
                "AAPL,Apple Inc,Technology,3000B,30.0,0.5,1.2,12.0,8.0,0.3,25.0,Test\n" +
                "MSFT,Microsoft Corp,Technology,2800B,35.0,0.8,0.9,15.0,10.0,0.2,30.0,Test\n"
        );
        return file;
    }

    private static TickerInfo ticker(String sym, String name, String sector) {
        return new TickerInfo(sym, name, sector, 500L, 25.0, 1.5, 1.2, 15.0, 12.0, 0.5, 18.0, null);
    }
}
