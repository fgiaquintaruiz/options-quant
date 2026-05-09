package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.TickerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@Order(2)
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class HistoricalBackfillService implements ApplicationRunner {

    private static final long THROTTLE_LOG_THRESHOLD_MS = 1_000;

    /**
     * Timeframe processing priority for backfill.
     *
     * <p>The outer loop iterates this list, and for each timeframe the inner loop
     * walks every ticker. This guarantees:
     * <ul>
     *   <li>DAY_1 (yfinance, fast, highest signal) completes for ALL tickers first.</li>
     *   <li>HOUR_1, then MIN_15, are processed across the full ticker universe.</li>
     *   <li>MIN_5 (slowest, lowest marginal value, highest TWS pacing risk) runs LAST,
     *       so an interrupted run never sacrifices broader-timeframe coverage.</li>
     * </ul>
     */
    static final List<TimeFrame> BACKFILL_PRIORITY_ORDER = List.of(
            TimeFrame.DAY_1,
            TimeFrame.HOUR_1,
            TimeFrame.MIN_15,
            TimeFrame.MIN_5
    );

    record BackfillPeriod(LocalDate from, LocalDate to) {}

    private final CandleRepository repository;
    private final BackfillCheckpoint checkpoint;
    private final IbkrService ibkrService;
    private final RateLimiter rateLimiter;
    private final List<String> tickers;
    private final ZonedDateTime backfillStart;
    private final YfinanceHistoricalClient yfinanceClient;
    private final List<String> vixTickers;
    private final List<BackfillPeriod> periods;
    private final TickerService tickerService;

    @Autowired
    public HistoricalBackfillService(
            CandleRepository repository,
            BackfillCheckpoint checkpoint,
            IbkrService ibkrService,
            @Value("${ibkr.tickers:#{T(java.util.Collections).emptyList()}}") List<String> tickers,
            @Value("${candles.backfill.start-year:2018}") int startYear,
            @Value("${candles.backfill.rate-per-second:0.1}") double ratePerSecond,
            @Autowired(required = false) YfinanceHistoricalClient yfinanceClient,
            @Value("${ibkr.vix-tickers:#{T(java.util.Collections).emptyList()}}") List<String> vixTickers,
            BackfillProperties backfillProperties,
            @Autowired(required = false) TickerService tickerService) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = RateLimiter.create(ratePerSecond);
        this.tickers = tickers;
        this.backfillStart = ZonedDateTime.of(startYear, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        this.yfinanceClient = yfinanceClient;
        this.vixTickers = vixTickers;
        this.periods = backfillProperties.parsedPeriods();
        this.tickerService = tickerService;

        log.info("HistoricalBackfillService initialized: {} tickers, rate={} req/s, startYear={}, periods={}",
                tickers.size(), ratePerSecond, startYear, this.periods.size());
    }

    HistoricalBackfillService(
            CandleRepository repository,
            BackfillCheckpoint checkpoint,
            IbkrService ibkrService,
            RateLimiter rateLimiter,
            List<String> tickers,
            ZonedDateTime backfillStart,
            YfinanceHistoricalClient yfinanceClient,
            List<String> vixTickers) {
        this(repository, checkpoint, ibkrService, rateLimiter, tickers, backfillStart,
                yfinanceClient, vixTickers, List.of(), null);
    }

    HistoricalBackfillService(
            CandleRepository repository,
            BackfillCheckpoint checkpoint,
            IbkrService ibkrService,
            RateLimiter rateLimiter,
            List<String> tickers,
            ZonedDateTime backfillStart,
            YfinanceHistoricalClient yfinanceClient,
            List<String> vixTickers,
            List<BackfillPeriod> periods) {
        this(repository, checkpoint, ibkrService, rateLimiter, tickers, backfillStart,
                yfinanceClient, vixTickers, periods, null);
    }

    HistoricalBackfillService(
            CandleRepository repository,
            BackfillCheckpoint checkpoint,
            IbkrService ibkrService,
            RateLimiter rateLimiter,
            List<String> tickers,
            ZonedDateTime backfillStart,
            YfinanceHistoricalClient yfinanceClient,
            List<String> vixTickers,
            List<BackfillPeriod> periods,
            TickerService tickerService) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = rateLimiter;
        this.tickers = tickers;
        this.backfillStart = backfillStart;
        this.yfinanceClient = yfinanceClient;
        this.vixTickers = vixTickers;
        this.periods = periods != null ? periods : List.of();
        this.tickerService = tickerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill")) {
            log.debug("--backfill flag not present. Historical backfill skipped.");
            return;
        }

        List<String> effectiveTickers = resolveTickerList(args);

        log.info("=== Historical Backfill START — {} tickers, from {} ===",
                effectiveTickers.size(), backfillStart.toLocalDate());

        long wallStart = System.currentTimeMillis();
        int totalCandles = 0;

        // Outer loop: timeframe priority (DAY_1 → HOUR_1 → MIN_15 → MIN_5).
        // Inner loop: every ticker. This ensures the most-valuable, fastest data
        // is captured for the entire universe before moving to slower timeframes.
        for (TimeFrame tf : BACKFILL_PRIORITY_ORDER) {
            log.info("=== Backfill phase START — timeframe {} ({} tickers) ===",
                    tf, effectiveTickers.size());

            int phaseCandles = 0;
            int tickersDone = 0;

            for (String ticker : effectiveTickers) {
                int candles = backfillTickerTimeframe(ticker, tf);
                phaseCandles += candles;
                tickersDone++;
                log.info("  [backfill] {} [{}] complete — {} candles (ticker {}/{})",
                        ticker, tf, candles, tickersDone, effectiveTickers.size());
            }

            // VIX tickers are yfinance-only and have only DAY_1 data. Process them
            // alongside the DAY_1 phase so a partial run still captures them.
            if (tf == TimeFrame.DAY_1) {
                for (String vixTicker : vixTickers) {
                    int candlesForVix = backfillTickerYfinanceOnly(vixTicker);
                    phaseCandles += candlesForVix;
                    log.info("  [backfill] {} (VIX) complete — {} candles", vixTicker, candlesForVix);
                }
            }

            totalCandles += phaseCandles;
            log.info("=== Backfill phase DONE — timeframe {}, {} candles ===", tf, phaseCandles);
        }

        long elapsed = System.currentTimeMillis() - wallStart;
        log.info("=== Historical Backfill DONE — {} tickers, {} candles total, {}s ===",
                effectiveTickers.size(), totalCandles, elapsed / 1000);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<String> resolveTickerList(ApplicationArguments args) {
        if (args.containsOption("backfill-all-tickers")) {
            if (tickerService == null) {
                throw new IllegalStateException(
                        "--backfill-all-tickers requires TickerService but none was injected");
            }
            List<String> all = tickerService.getTickerSymbols();
            if (all.isEmpty()) {
                log.warn("[backfill] --backfill-all-tickers: universe is empty — skipping");
            }
            log.info("[backfill] Mode: ALL_TICKERS ({} symbols)", all.size());
            return all;
        }
        log.info("[backfill] Mode: CONFIGURED_TICKERS ({} symbols)", this.tickers.size());
        return this.tickers;
    }

    private int backfillTickerTimeframe(String ticker, TimeFrame tf) {
        int stored = 0;

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        Optional<ZonedDateTime> lastDone = checkpoint.getLastDownloaded(ticker, tf);
        ZonedDateTime chunkFrom = lastDone.orElse(backfillStart);

        while (chunkFrom.isBefore(now)) {
            ZonedDateTime chunkTo = chunkFrom.plus(chunkDuration(tf));
            if (chunkTo.isAfter(now)) {
                chunkTo = now;
            }

            if (!isChunkInAnyPeriod(chunkFrom, chunkTo)) {
                log.info("  [backfill] Skipping {} [{}] {}–{}: OUT_OF_RANGE",
                        ticker, tf, chunkFrom.toLocalDate(), chunkTo.toLocalDate());
                chunkFrom = chunkTo;
                continue;
            }

            for (BackfillPeriod effective : computeEffectiveRanges(chunkFrom, chunkTo)) {
                ZonedDateTime effectiveFrom = effective.from().atStartOfDay(ZoneOffset.UTC);
                ZonedDateTime effectiveTo = effective.to().atStartOfDay(ZoneOffset.UTC);
                stored += downloadChunk(ticker, tf, effectiveFrom, effectiveTo);
            }

            chunkFrom = chunkTo;
        }

        return stored;
    }

    private int downloadChunk(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to) {
        long throttleStart = System.currentTimeMillis();
        rateLimiter.acquire();
        long waited = System.currentTimeMillis() - throttleStart;
        if (waited > THROTTLE_LOG_THRESHOLD_MS) {
            log.warn("  [backfill] Rate limiter throttled {}ms before downloading {} [{}] chunk {}",
                    waited, ticker, tf, from.toLocalDate());
        }

        if (tf == TimeFrame.DAY_1) {
            return downloadChunkYfinance(ticker, from, to);
        }

        return downloadChunkTwsOnly(ticker, tf, from, to);
    }

    private int downloadChunkTwsOnly(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to) {
        try {
            List<Candle> candles = ibkrService.downloadHistoricalData(ticker, tf, to);
            if (!candles.isEmpty()) repository.upsert(ticker, tf, candles);
            checkpoint.save(ticker, tf, to, BackfillStatus.COMPLETE_TWS);
            return candles.size();
        } catch (Exception e) {
            log.error("  [backfill] TWS error for {} [{}] chunk {}: {}", ticker, tf, from.toLocalDate(), e.getMessage());
            return 0;
        }
    }

    private int downloadChunkYfinance(String ticker, ZonedDateTime from, ZonedDateTime to) {
        try {
            List<Candle> candles = yfinanceClient.fetchDailyCandles(ticker, from.toLocalDate(), to.toLocalDate());
            if (!candles.isEmpty()) repository.upsert(ticker, TimeFrame.DAY_1, candles);
            checkpoint.save(ticker, TimeFrame.DAY_1, to,
                    candles.isEmpty() ? BackfillStatus.COMPLETE_EMPTY : BackfillStatus.COMPLETE_YFINANCE);
            log.debug("  [backfill] yfinance {} {} → {} — {} candles",
                    ticker, from.toLocalDate(), to.toLocalDate(), candles.size());
            return candles.size();
        } catch (Exception e) {
            log.warn("[backfill] yfinance error for {} [{}/{}]: {}", ticker, from.toLocalDate(), to.toLocalDate(), e.getMessage());
            return 0;
        }
    }

    private int backfillTickerYfinanceOnly(String vixTicker) {
        int stored = 0;
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Optional<ZonedDateTime> lastDone = checkpoint.getLastDownloaded(vixTicker, TimeFrame.DAY_1);
        ZonedDateTime chunkFrom = lastDone.orElse(backfillStart);
        while (chunkFrom.isBefore(now)) {
            ZonedDateTime chunkTo = chunkFrom.plus(chunkDuration(TimeFrame.DAY_1));
            if (chunkTo.isAfter(now)) chunkTo = now;

            if (!isChunkInAnyPeriod(chunkFrom, chunkTo)) {
                log.info("  [backfill] Skipping {} [DAY_1] {}–{}: OUT_OF_RANGE",
                        vixTicker, chunkFrom.toLocalDate(), chunkTo.toLocalDate());
                chunkFrom = chunkTo;
                continue;
            }

            for (BackfillPeriod effective : computeEffectiveRanges(chunkFrom, chunkTo)) {
                ZonedDateTime effectiveFrom = effective.from().atStartOfDay(ZoneOffset.UTC);
                ZonedDateTime effectiveTo = effective.to().atStartOfDay(ZoneOffset.UTC);
                stored += downloadChunkYfinance(vixTicker, effectiveFrom, effectiveTo);
            }
            chunkFrom = chunkTo;
        }
        return stored;
    }

    /**
     * Returns the merged intersections of [chunkFrom, chunkTo) with all configured periods.
     * Adjacent or overlapping intersections are merged into a single range.
     * When periods is empty the full chunk is returned as-is (no trimming).
     */
    List<BackfillPeriod> computeEffectiveRanges(ZonedDateTime chunkFrom, ZonedDateTime chunkTo) {
        if (periods.isEmpty()) {
            return List.of(new BackfillPeriod(chunkFrom.toLocalDate(), chunkTo.toLocalDate()));
        }

        List<BackfillPeriod> intersections = new ArrayList<>();
        LocalDate cFrom = chunkFrom.toLocalDate();
        LocalDate cTo = chunkTo.toLocalDate();

        for (BackfillPeriod p : periods) {
            if (cFrom.isAfter(p.to()) || cTo.isBefore(p.from())) continue;
            LocalDate iFrom = cFrom.isAfter(p.from()) ? cFrom : p.from();
            LocalDate iTo = cTo.isBefore(p.to()) ? cTo : p.to();
            intersections.add(new BackfillPeriod(iFrom, iTo));
        }

        if (intersections.isEmpty()) {
            return List.of();
        }

        intersections.sort(Comparator.comparing(BackfillPeriod::from));
        List<BackfillPeriod> merged = new ArrayList<>();
        BackfillPeriod current = intersections.get(0);
        for (int i = 1; i < intersections.size(); i++) {
            BackfillPeriod next = intersections.get(i);
            if (!next.from().isAfter(current.to().plusDays(1))) {
                LocalDate mergedTo = current.to().isAfter(next.to()) ? current.to() : next.to();
                current = new BackfillPeriod(current.from(), mergedTo);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private boolean isChunkInAnyPeriod(ZonedDateTime chunkFrom, ZonedDateTime chunkTo) {
        if (periods.isEmpty()) return true;
        LocalDate from = chunkFrom.toLocalDate();
        LocalDate to = chunkTo.toLocalDate();
        return periods.stream()
                .anyMatch(p -> !from.isAfter(p.to()) && !to.isBefore(p.from()));
    }

    private Duration chunkDuration(TimeFrame tf) {
        return switch (tf) {
            case MIN_5 -> Duration.ofDays(30);
            case MIN_15 -> Duration.ofDays(90);
            case HOUR_1 -> Duration.ofDays(90);
            case DAY_1 -> Duration.ofDays(365);
        };
    }

    private ZonedDateTime chunkEnd(ZonedDateTime from, TimeFrame tf) {
        return from.plus(chunkDuration(tf));
    }
}
