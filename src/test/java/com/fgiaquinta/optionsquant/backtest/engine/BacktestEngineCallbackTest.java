package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TDD — RED tests written BEFORE modifying BacktestConfig / BacktestEngine.
 *
 * <p>These tests validate:
 * <ol>
 *   <li>BacktestConfig can carry an optional {@code onTickerComplete} callback.</li>
 *   <li>{@code defaults()} factory still works without a callback (backward compat).</li>
 *   <li>A callback-aware factory/builder constructs correctly.</li>
 * </ol>
 *
 * <p>Note: end-to-end engine invocation tests that require real candle data are
 * integration-scope. These unit tests focus on the config contract and the
 * callback wiring contract that BacktestBatchRunner will use.
 */
class BacktestEngineCallbackTest {

    // -------------------------------------------------------------------------
    // T1 — BacktestConfig.defaults() has null callback (backward compat)
    // -------------------------------------------------------------------------

    @Test
    void defaults_hasNullCallback() {
        BacktestConfig config = BacktestConfig.defaults(
                List.of("AAPL"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31)
        );

        assertThat(config.onTickerComplete()).isNull();
    }

    // -------------------------------------------------------------------------
    // T2 — withCallback factory accepts a BiConsumer and stores it
    // -------------------------------------------------------------------------

    @Test
    void withCallback_storesTheCallback() {
        BiConsumer<String, List<TradeRecord>> callback = (ticker, trades) -> {};

        BacktestConfig config = BacktestConfig.withCallback(
                List.of("AAPL", "MSFT"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                callback
        );

        assertThat(config.onTickerComplete()).isSameAs(callback);
    }

    // -------------------------------------------------------------------------
    // T3 — withCallback preserves same default numeric values as defaults()
    // -------------------------------------------------------------------------

    @Test
    void withCallback_preservesDefaultNumericValues() {
        BacktestConfig defaults = BacktestConfig.defaults(
                List.of("AAPL"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31)
        );

        BacktestConfig withCb = BacktestConfig.withCallback(
                List.of("AAPL"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                (ticker, trades) -> {}
        );

        assertThat(withCb.initialCapital()).isEqualTo(defaults.initialCapital());
        assertThat(withCb.riskPerTradePct()).isEqualTo(defaults.riskPerTradePct());
        assertThat(withCb.slippagePct()).isEqualTo(defaults.slippagePct());
        assertThat(withCb.commissionPerContract()).isEqualTo(defaults.commissionPerContract());
        assertThat(withCb.maxConcurrentTrades()).isEqualTo(defaults.maxConcurrentTrades());
        assertThat(withCb.executionTimeframe()).isEqualTo(defaults.executionTimeframe());
        assertThat(withCb.includeTradePlans()).isEqualTo(defaults.includeTradePlans());
        assertThat(withCb.deterministicMode()).isEqualTo(defaults.deterministicMode());
        assertThat(withCb.tpMultiplierDelta()).isEqualTo(defaults.tpMultiplierDelta());
        assertThat(withCb.slMultiplierDelta()).isEqualTo(defaults.slMultiplierDelta());
    }

    // -------------------------------------------------------------------------
    // T4 — BacktestConfig compact constructor validates even with null callback
    // -------------------------------------------------------------------------

    @Test
    void config_withNullCallback_doesNotThrow() {
        assertThatCode(() -> BacktestConfig.defaults(
                List.of("AAPL"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31)
        )).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // T5 — callback invocable from multiple threads without exception
    // -------------------------------------------------------------------------

    @Test
    void callback_isInvocableFromMultipleThreads() throws InterruptedException {
        List<String> receivedTickers = new CopyOnWriteArrayList<>();

        BiConsumer<String, List<TradeRecord>> callback =
                (ticker, trades) -> receivedTickers.add(ticker);

        BacktestConfig config = BacktestConfig.withCallback(
                List.of("AAPL", "MSFT", "GOOG"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                callback
        );

        // Simulate concurrent invocation from worker threads
        List<Thread> threads = new ArrayList<>();
        for (String ticker : List.of("AAPL", "MSFT", "GOOG")) {
            Thread t = new Thread(() ->
                    config.onTickerComplete().accept(ticker, List.of()));
            threads.add(t);
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join(1000);

        assertThat(receivedTickers).containsExactlyInAnyOrder("AAPL", "MSFT", "GOOG");
    }
}
