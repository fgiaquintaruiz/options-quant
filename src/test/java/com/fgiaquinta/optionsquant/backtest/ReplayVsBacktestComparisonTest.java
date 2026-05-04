package com.fgiaquinta.optionsquant.backtest;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.candle.csv.CsvCandleRepository;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.ReplayCandleSource;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparison test: Backtest engine and Replay candle source must agree on the
 * same candle dataset when given identical CSV data.
 *
 * <p>Verifies three invariants without a real TWS connection:
 * <ol>
 *   <li>Both paths load the same number of MIN_15 candles for the same symbol.</li>
 *   <li>BacktestEngine completes without exceptions and returns a non-null report.</li>
 *   <li>ReplayCandleSource exposes all candles at virtualNow = last candle timestamp.</li>
 * </ol>
 *
 * <p>CSV fixture: {@code src/test/resources/replay/SPY_15min.csv} — 5 bars on 2026-04-22.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
class ReplayVsBacktestComparisonTest {

    // Replay date matching the fixture timestamps
    private static final LocalDate REPLAY_DATE = LocalDate.of(2026, 4, 22);

    // Last candle timestamp in the fixture (2026-04-22 15:30 system-local)
    // CandleCsvService parses timestamps using ZoneId.systemDefault(), so we use
    // the same zone to guarantee the comparison works regardless of CI timezone.
    private static final ZonedDateTime LAST_CANDLE_TS =
            REPLAY_DATE.atTime(15, 30).atZone(ZoneId.systemDefault());

    // Number of MIN_15 bars in the fixture file
    private static final int FIXTURE_CANDLE_COUNT = 5;

    @TempDir
    Path tempDataDir;

    @Mock
    private TickerMemory tickerMemory;

    @Mock
    private IbkrService ibkrService;

    private CandleRepository candleRepository;

    @BeforeEach
    void copyFixturesToTempDir() throws Exception {
        // Copy every fixture CSV to the temp directory so CandleCsvService
        // can load them without touching real disk data.
        for (String file : List.of("SPY_15min.csv", "SPY_5min.csv", "SPY_1hour.csv", "SPY_1day.csv")) {
            String resourcePath = "/replay/" + file;
            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                assertThat(in)
                        .as("fixture %s must exist in src/test/resources/replay/", file)
                        .isNotNull();
                Files.copy(in, tempDataDir.resolve(file));
            }
        }

        CsvCandleRepository csvRepo = new CsvCandleRepository();
        csvRepo.setDataDir(tempDataDir);
        this.candleRepository = csvRepo;
    }

    // =========================================================================
    // 1.  Backtest path — candle count and smoke
    // =========================================================================

    @Test
    @DisplayName("BacktestEngine processes all MIN_15 fixture candles for SPY without exceptions")
    void backtest_processesAllFixtureCandles() {
        BacktestEngine engine = new BacktestEngine(candleRepository, tickerMemory, 1, false);

        BacktestConfig config = BacktestConfig.defaults(
                List.of("SPY"),
                REPLAY_DATE,
                REPLAY_DATE
        );

        // Should complete without exception
        BacktestReport report = engine.run(config, false, null);

        assertThat(report).isNotNull();
        // The engine loads all candles for MIN_15 in the date range — the fixture has 5 bars.
        // Regardless of whether strategies trigger, the report must reflect a run.
        assertThat(report.initialCapital()).isEqualTo(config.initialCapital());
    }

    // =========================================================================
    // 2.  Replay path — candle count
    // =========================================================================

    @Test
    @DisplayName("ReplayCandleSource exposes all MIN_15 fixture candles at virtualNow = last bar")
    void replay_exposesAllFixtureCandlesAtLastTimestamp() {
        // ibkrService.isConnected() is never called because the CSV already covers REPLAY_DATE.
        // ReplayCandleSource only contacts IBKR when the CSV is stale/missing for the target date.
        ReplayCandleSource source = new ReplayCandleSource(candleRepository, ibkrService);
        source.preload(REPLAY_DATE, Set.of("SPY"), List.of(TimeFrame.MIN_15));

        List<Candle> visible = source.getCandlesUntil("SPY", TimeFrame.MIN_15, LAST_CANDLE_TS);

        assertThat(visible)
                .as("replay must expose all %d bars at virtualNow = last candle timestamp", FIXTURE_CANDLE_COUNT)
                .hasSize(FIXTURE_CANDLE_COUNT);
    }

    // =========================================================================
    // 3.  Data parity — both paths load the same candle set
    // =========================================================================

    @Test
    @DisplayName("Backtest and Replay-mode load the same MIN_15 candle count for SPY")
    void backtestAndReplay_agreesOnCandleCount() {
        // --- Replay side ---
        ReplayCandleSource source = new ReplayCandleSource(candleRepository, ibkrService);
        source.preload(REPLAY_DATE, Set.of("SPY"), List.of(TimeFrame.MIN_15));

        List<Candle> replayCandles = source.getCandlesUntil("SPY", TimeFrame.MIN_15, LAST_CANDLE_TS);

        // --- Backtest side ---
        // BacktestEngine loads via candleRepository.load then filters to [fromDate, toDate].
        // We replicate that filter here to get the count the engine actually saw.
        List<Candle> backtestCandles = candleRepository.load("SPY", TimeFrame.MIN_15)
                .stream()
                .filter(c -> !c.timestamp().toLocalDate().isBefore(REPLAY_DATE)
                          && !c.timestamp().toLocalDate().isAfter(REPLAY_DATE))
                .toList();

        // --- Assert parity ---
        assertThat(replayCandles).hasSameSizeAs(backtestCandles);
        assertThat(replayCandles.size()).isEqualTo(FIXTURE_CANDLE_COUNT);

        // Candle timestamps must be in the same order
        for (int i = 0; i < replayCandles.size(); i++) {
            assertThat(replayCandles.get(i).timestamp())
                    .as("candle[%d] timestamp mismatch between replay and backtest", i)
                    .isEqualTo(backtestCandles.get(i).timestamp());
        }
    }

    // =========================================================================
    // 4.  Replay window semantics — partial visibility
    // =========================================================================

    @Test
    @DisplayName("ReplayCandleSource returns only candles up to virtualNow (partial window)")
    void replay_partialWindowReturnsSubset() {
        ReplayCandleSource source = new ReplayCandleSource(candleRepository, ibkrService);
        source.preload(REPLAY_DATE, Set.of("SPY"), List.of(TimeFrame.MIN_15));

        // virtualNow = 3rd bar (15:00), so only bars 1-3 should be visible
        ZonedDateTime thirdBarTs = REPLAY_DATE.atTime(15, 0).atZone(ZoneId.systemDefault());
        List<Candle> partial = source.getCandlesUntil("SPY", TimeFrame.MIN_15, thirdBarTs);

        assertThat(partial)
                .as("only candles with timestamp <= 15:00 should be visible")
                .hasSize(3);
        assertThat(partial.get(2).timestamp())
                .isEqualTo(thirdBarTs);
    }

    // =========================================================================
    // 5.  Processing order consistency — replay candles must be chronological
    // =========================================================================

    @Test
    @DisplayName("Replay candles are returned in chronological order (consistent processing order)")
    void replay_candlesAreChronologicallyOrdered() {
        ReplayCandleSource source = new ReplayCandleSource(candleRepository, ibkrService);
        source.preload(REPLAY_DATE, Set.of("SPY"), List.of(TimeFrame.MIN_15));

        List<Candle> candles = source.getCandlesUntil("SPY", TimeFrame.MIN_15, LAST_CANDLE_TS);

        for (int i = 1; i < candles.size(); i++) {
            assertThat(candles.get(i).timestamp())
                    .as("candle[%d] must be after candle[%d]", i, i - 1)
                    .isAfter(candles.get(i - 1).timestamp());
        }
    }
}
