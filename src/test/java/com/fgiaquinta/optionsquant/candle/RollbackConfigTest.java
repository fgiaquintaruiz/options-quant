package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.candle.csv.CsvCandleRepository;
import com.fgiaquinta.optionsquant.candle.sqlite.SqliteCandleRepository;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import com.fgiaquinta.optionsquant.service.AccountManager;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * T24 — Rollback config test.
 *
 * Verifies that the Spring conditional wiring selects the correct
 * {@link CandleRepository} implementation based on {@code candles.store}.
 *
 * No data read/write occurs — purely verifies which bean is injected.
 */
class RollbackConfigTest {

    /**
     * When {@code candles.store=sqlite} (the default), {@link SqliteCandleRepository}
     * must be the active {@link CandleRepository} bean.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
        "candles.store=sqlite",
        "candles.sqlite.path=:memory:"
    })
    static class SqliteActiveTest {

        @MockitoBean IbkrService ibkrService;
        @MockitoBean AccountManager accountManager;
        @MockitoBean OrderExecutionService orderExecutionService;
        @MockitoBean MetricsService metricsService;

        @Autowired
        CandleRepository candleRepository;

        @Test
        void candleStore_sqlite_injectsSqliteCandleRepository() {
            assertInstanceOf(SqliteCandleRepository.class, candleRepository,
                "When candles.store=sqlite, the injected CandleRepository must be SqliteCandleRepository");
        }
    }

    /**
     * When {@code candles.store=csv}, {@link CsvCandleRepository} must be the
     * active {@link CandleRepository} bean and SQLite beans must not be created.
     *
     * JDBC DataSource autoconfiguration is excluded via property because in CSV mode
     * no DataSource is configured — the app uses filesystem CSV files only.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
        "candles.store=csv",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration," +
            "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
    })
    static class CsvActiveTest {

        @MockitoBean IbkrService ibkrService;
        @MockitoBean AccountManager accountManager;
        @MockitoBean OrderExecutionService orderExecutionService;
        @MockitoBean MetricsService metricsService;

        @Autowired
        CandleRepository candleRepository;

        @Test
        void candleStore_csv_injectsCsvCandleRepository() {
            assertInstanceOf(CsvCandleRepository.class, candleRepository,
                "When candles.store=csv, the injected CandleRepository must be CsvCandleRepository");
        }
    }
}
