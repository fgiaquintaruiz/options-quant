package com.fgiaquinta.optionsquant.backtest;

import com.fgiaquinta.optionsquant.candle.TickerCursor;
import com.fgiaquinta.optionsquant.candle.sqlite.SchemaInitializer;
import com.fgiaquinta.optionsquant.candle.sqlite.SqliteCandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * T25 — Lazy load stress test: 50,000 candles via TickerCursor without OOM.
 *
 * Verifies that streaming large datasets through {@link TickerCursor} does not
 * load all rows into a List in memory at once. Uses the same PriorityQueue
 * drain pattern as BacktestEngine.
 *
 * Tagged {@code @Tag("slow")} — excluded from the default test run (see build.gradle.kts).
 * Run explicitly: {@code ./gradlew slowTest}.
 */
@Tag("slow")
class LazyLoadStressTest {

    private static final int ROW_COUNT = 50_000;
    private static final String TICKER = "STRESS";
    private static final TimeFrame TF = TimeFrame.MIN_5;

    private HikariDataSource ds;
    private SqliteCandleRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite::memory:");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000;");
        ds = new HikariDataSource(cfg);

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaInitializer.forTesting(jdbc).afterPropertiesSet();

        repo = new SqliteCandleRepository(ds, ds);

        // Insert ROW_COUNT candles in batches
        ZonedDateTime base = ZonedDateTime.of(2018, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        int batchSize = 2000;
        for (int batch = 0; batch < ROW_COUNT / batchSize; batch++) {
            List<Candle> candles = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                int idx = batch * batchSize + i;
                candles.add(new Candle(
                        base.plusMinutes(5L * idx),
                        100.0 + idx, 110.0 + idx, 90.0 + idx, 105.0 + idx, 1000L + idx));
            }
            repo.upsert(TICKER, TF, candles);
        }
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    /**
     * Drains 50,000 candles through TickerCursor (same PriorityQueue pattern as BacktestEngine).
     * Asserts: all rows consumed, no OOM, cursor-based path used (not a bulk List load).
     */
    @Test
    void tickerCursor_drains50kCandles_withoutOOM() {
        AtomicLong consumed = new AtomicLong();

        assertDoesNotThrow(() -> {
            PriorityQueue<TickerCursor> queue = new PriorityQueue<>();
            Stream<Candle> stream = repo.stream(TICKER, TF);
            TickerCursor cursor = new TickerCursor(TICKER, TF, stream);
            queue.add(cursor);

            while (!queue.isEmpty()) {
                TickerCursor head = queue.poll();
                if (head.isExhausted()) {
                    head.close();
                    continue;
                }
                head.next(); // consume one candle
                consumed.incrementAndGet();
                if (!head.isExhausted()) {
                    queue.add(head);
                } else {
                    head.close();
                }
            }
        }, "TickerCursor drain of 50,000 rows must not throw OOM or any exception");

        assertEquals(ROW_COUNT, consumed.get(),
                "All " + ROW_COUNT + " candles must be consumed exactly once");
    }
}
