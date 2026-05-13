package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.MarketCalendarService;
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
import java.time.ZoneId;
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
    private final BackfillPeriod liveTailPeriod;
    private final TickerService tickerService;
    private final BackfillProgressTracker progressTracker;
    private final MarketCalendarService marketCalendarService;

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
            @Autowired(required = false) TickerService tickerService,
            BackfillProgressTracker progressTracker,
            MarketCalendarService marketCalendarService) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = RateLimiter.create(ratePerSecond);
        this.tickers = tickers;
        this.backfillStart = ZonedDateTime.of(startYear, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        this.yfinanceClient = yfinanceClient;
        this.vixTickers = vixTickers;
        this.periods = backfillProperties.parsedPeriodsWithLiveTail();
        this.liveTailPeriod = backfillProperties.livetailPeriod();
        this.tickerService = tickerService;
        this.progressTracker = progressTracker;
        this.marketCalendarService = marketCalendarService;

        log.info("HistoricalBackfillService initialized: {} tickers, rate={} req/s, startYear={}, periods={} (live_tail={})",
                tickers.size(), ratePerSecond, startYear, this.periods.size(),
                this.liveTailPeriod == null ? "none" : this.liveTailPeriod.from() + "→" + this.liveTailPeriod.to());
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
                yfinanceClient, vixTickers, List.of(), null, null);
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
                yfinanceClient, vixTickers, periods, null, null);
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
        this(repository, checkpoint, ibkrService, rateLimiter, tickers, backfillStart,
                yfinanceClient, vixTickers, periods, tickerService, null);
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
            TickerService tickerService,
            MarketCalendarService marketCalendarService) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = rateLimiter;
        this.tickers = tickers;
        this.backfillStart = backfillStart;
        this.yfinanceClient = yfinanceClient;
        this.vixTickers = vixTickers;
        this.periods = periods != null ? periods : List.of();
        // Test-path: derive live_tail as the period whose `to` equals today (the dynamic one).
        // Production wires liveTailPeriod via the BackfillProperties-aware constructor.
        this.liveTailPeriod = deriveLiveTailFromPeriods(this.periods);
        this.tickerService = tickerService;
        this.progressTracker = new BackfillProgressTracker(10);
        this.marketCalendarService = marketCalendarService;
    }

    private static BackfillPeriod deriveLiveTailFromPeriods(List<BackfillPeriod> periods) {
        if (periods.isEmpty()) return null;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // The live_tail is, by construction, the unique period whose `to` equals today.
        return periods.stream()
                .filter(p -> p.to().equals(today))
                .findFirst()
                .orElse(null);
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

        // Check market open status ONCE before processing any live_tail chunks.
        // Historical chunks are always downloaded regardless of market hours.
        boolean marketOpen = isLiveTailMarketOpen(effectiveTickers);

        long wallStart = System.currentTimeMillis();
        int totalCandles = 0;

        // Outer loop: timeframe priority (DAY_1 → HOUR_1 → MIN_15 → MIN_5).
        // Inner loop: every ticker. This ensures the most-valuable, fastest data
        // is captured for the entire universe before moving to slower timeframes.
        for (TimeFrame tf : BACKFILL_PRIORITY_ORDER) {
            log.info("=== Backfill phase START — timeframe {} ({} tickers) ===",
                    tf, effectiveTickers.size());
            progressTracker.startPhase(tf.name(), effectiveTickers.size());

            int phaseCandles = 0;
            int tickersDone = 0;

            for (String ticker : effectiveTickers) {
                int candles = backfillTickerTimeframe(ticker, tf, marketOpen);
                phaseCandles += candles;
                tickersDone++;
                progressTracker.recordTicker();
                log.info("  [backfill] {} [{}] complete — {} candles (ticker {}/{})",
                        ticker, tf, candles, tickersDone, effectiveTickers.size());
            }

            // VIX tickers are yfinance-only and have only DAY_1 data. Process them
            // alongside the DAY_1 phase so a partial run still captures them.
            if (tf == TimeFrame.DAY_1) {
                for (String vixTicker : vixTickers) {
                    int candlesForVix = backfillTickerYfinanceOnly(vixTicker, marketOpen);
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

    /**
     * Returns true when the US equity market is currently in regular hours (9:30–16:00 ET,
     * Mon–Fri, excluding NYSE holidays). Consulted ONCE per run, before any live_tail chunk
     * is processed. Returns true unconditionally when no live_tail period is configured or
     * when {@link MarketCalendarService} is not wired (e.g. in some test setups).
     */
    private boolean isLiveTailMarketOpen(List<String> effectiveTickers) {
        if (liveTailPeriod == null || marketCalendarService == null) {
            return true;
        }
        ZonedDateTime nowEt = ZonedDateTime.now(ZoneId.of("America/New_York"));
        boolean open = marketCalendarService.isRegularMarketHours(nowEt);
        if (!open) {
            log.info("[live_tail] Market closed — skipping live_tail phase ({} tickers × {} timeframes)",
                    effectiveTickers.size(), BACKFILL_PRIORITY_ORDER.size());
        }
        return open;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<String> resolveTickerList(ApplicationArguments args) {
        List<String> candidates;
        if (args.containsOption("backfill-all-tickers")) {
            if (tickerService == null) {
                throw new IllegalStateException(
                        "--backfill-all-tickers requires TickerService but none was injected");
            }
            candidates = tickerService.getTickerSymbols();
            if (candidates.isEmpty()) {
                log.warn("[backfill] --backfill-all-tickers: universe is empty — skipping");
            }
            log.info("[backfill] Mode: ALL_TICKERS ({} symbols)", candidates.size());
        } else {
            candidates = this.tickers;
            log.info("[backfill] Mode: CONFIGURED_TICKERS ({} symbols)", candidates.size());
        }

        boolean retryPermanentSkips = args.containsOption("retry-permanent-skips");
        if (retryPermanentSkips) {
            log.info("[backfill] --retry-permanent-skips: including SKIPPED_PERMANENT tickers");
            return candidates;
        }

        // Exclude tickers where ALL timeframes have SKIPPED_PERMANENT status.
        // These tickers never existed on TWS (error 200) and would loop forever.
        List<String> effective = candidates.stream()
                .filter(ticker -> !checkpoint.isAllTimeframesPermanentlySkipped(ticker))
                .toList();

        int excluded = candidates.size() - effective.size();
        if (excluded > 0) {
            log.info("[backfill] Excluded {} SKIPPED_PERMANENT tickers (pass --retry-permanent-skips to override)",
                    excluded);
        }
        return effective;
    }

    private int backfillTickerTimeframe(String ticker, TimeFrame tf, boolean marketOpen) {
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

            ChunkOrigin origin = isChunkInLiveTail(chunkFrom, chunkTo) ? ChunkOrigin.LIVE_TAIL : ChunkOrigin.HISTORICAL;

            if (origin == ChunkOrigin.LIVE_TAIL && !marketOpen) {
                log.debug("  [live_tail] Skipping {} [{}] {}–{}: market closed",
                        ticker, tf, chunkFrom.toLocalDate(), chunkTo.toLocalDate());
                chunkFrom = chunkTo;
                continue;
            }

            String originTag = origin == ChunkOrigin.LIVE_TAIL ? "[live_tail]" : "[historical]";
            log.info("  [backfill] {} {} [{}] {}–{}: downloading", originTag,
                    ticker, tf, chunkFrom.toLocalDate(), chunkTo.toLocalDate());

            for (BackfillPeriod effective : computeEffectiveRanges(chunkFrom, chunkTo)) {
                ZonedDateTime effectiveFrom = effective.from().atStartOfDay(ZoneOffset.UTC);
                ZonedDateTime effectiveTo = effective.to().atStartOfDay(ZoneOffset.UTC);
                stored += downloadChunk(ticker, tf, effectiveFrom, effectiveTo, origin);
            }

            chunkFrom = chunkTo;
        }

        return stored;
    }

    private int downloadChunk(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to, ChunkOrigin origin) {
        long throttleStart = System.currentTimeMillis();
        rateLimiter.acquire();
        long waited = System.currentTimeMillis() - throttleStart;
        if (waited > THROTTLE_LOG_THRESHOLD_MS) {
            log.warn("  [backfill] Rate limiter throttled {}ms before downloading {} [{}] chunk {}",
                    waited, ticker, tf, from.toLocalDate());
        }

        if (tf == TimeFrame.DAY_1) {
            return downloadChunkYfinance(ticker, from, to, origin);
        }

        return downloadChunkTwsOnly(ticker, tf, from, to, origin);
    }

    private static final int TWS_ERROR_NO_SECURITY_DEFINITION = 200;

    private int downloadChunkTwsOnly(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to, ChunkOrigin origin) {
        try {
            List<Candle> candles = ibkrService.downloadHistoricalData(ticker, tf, to);
            if (!candles.isEmpty()) repository.upsert(ticker, tf, candles);
            persistCheckpoint(ticker, tf, to, BackfillStatus.COMPLETE_TWS, origin);
            return candles.size();
        } catch (IbkrHistoricalDataException e) {
            if (e.getErrorCode() == TWS_ERROR_NO_SECURITY_DEFINITION) {
                // Permanent failure: security never existed or is not available via TWS.
                // Write SKIPPED_PERMANENT so this ticker is excluded from future runs.
                log.warn("  [backfill] TWS error 200 for {} [{}] chunk {} — SKIPPED_PERMANENT: {}",
                        ticker, tf, from.toLocalDate(), e.getMessage());
                persistCheckpointWithSkip(ticker, tf, to, BackfillStatus.SKIPPED_PERMANENT, origin,
                        TWS_ERROR_NO_SECURITY_DEFINITION);
                progressTracker.recordError();
            } else {
                // Transient error (e.g. 162=pacing, 321=unknown) — leave NEEDS_RESUME for retry.
                log.warn("  [backfill] TWS error {} for {} [{}] chunk {} — leaving NEEDS_RESUME: {}",
                        e.getErrorCode(), ticker, tf, from.toLocalDate(), e.getMessage());
                progressTracker.recordError();
            }
            return 0;
        } catch (Exception e) {
            log.error("  [backfill] TWS error for {} [{}] chunk {}: {}", ticker, tf, from.toLocalDate(), e.getMessage());
            return 0;
        }
    }

    /**
     * Persists a SKIPPED_PERMANENT checkpoint with an audit error code.
     */
    private void persistCheckpointWithSkip(String ticker, TimeFrame tf, ZonedDateTime to,
                                           BackfillStatus status, ChunkOrigin origin,
                                           Integer skipErrorCode) {
        checkpoint.save(ticker, tf, to, status, origin, skipErrorCode);
    }

    private int downloadChunkYfinance(String ticker, ZonedDateTime from, ZonedDateTime to, ChunkOrigin origin) {
        try {
            List<Candle> candles = yfinanceClient.fetchDailyCandles(ticker, from.toLocalDate(), to.toLocalDate());
            if (!candles.isEmpty()) repository.upsert(ticker, TimeFrame.DAY_1, candles);
            persistCheckpoint(ticker, TimeFrame.DAY_1, to,
                    candles.isEmpty() ? BackfillStatus.COMPLETE_EMPTY : BackfillStatus.COMPLETE_YFINANCE, origin);
            log.debug("  [backfill] yfinance {} {} → {} — {} candles",
                    ticker, from.toLocalDate(), to.toLocalDate(), candles.size());
            return candles.size();
        } catch (YfinanceFetchException e) {
            // Fetch failed — do NOT write a checkpoint; ticker stays NEEDS_RESUME for retry
            log.warn("[backfill] yfinance fetch failed for {} [{}/{}] — skipping checkpoint: {}",
                    ticker, from.toLocalDate(), to.toLocalDate(), e.getMessage());
            progressTracker.recordError();
            return 0;
        } catch (Exception e) {
            log.warn("[backfill] yfinance error for {} [{}/{}]: {}", ticker, from.toLocalDate(), to.toLocalDate(), e.getMessage());
            return 0;
        }
    }

    /**
     * Routes checkpoint persistence to the right BackfillCheckpoint overload.
     *
     * <p>HISTORICAL chunks use the legacy 4-arg {@code save} signature (which itself
     * delegates to the 5-arg overload with {@code chunk_origin = 'HISTORICAL'}).
     * LIVE_TAIL chunks call the 5-arg overload directly so {@code chunk_origin = 'LIVE_TAIL'}
     * is persisted.
     */
    private void persistCheckpoint(String ticker, TimeFrame tf, ZonedDateTime to,
                                   BackfillStatus status, ChunkOrigin origin) {
        if (origin == ChunkOrigin.LIVE_TAIL) {
            checkpoint.save(ticker, tf, to, status, origin);
        } else {
            checkpoint.save(ticker, tf, to, status);
        }
    }

    private int backfillTickerYfinanceOnly(String vixTicker, boolean marketOpen) {
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

            ChunkOrigin origin = isChunkInLiveTail(chunkFrom, chunkTo) ? ChunkOrigin.LIVE_TAIL : ChunkOrigin.HISTORICAL;

            if (origin == ChunkOrigin.LIVE_TAIL && !marketOpen) {
                log.debug("  [live_tail] Skipping {} (VIX) [DAY_1] {}–{}: market closed",
                        vixTicker, chunkFrom.toLocalDate(), chunkTo.toLocalDate());
                chunkFrom = chunkTo;
                continue;
            }

            String originTag = origin == ChunkOrigin.LIVE_TAIL ? "[live_tail]" : "[historical]";
            log.info("  [backfill] {} {} (VIX) [DAY_1] {}–{}: downloading", originTag,
                    vixTicker, chunkFrom.toLocalDate(), chunkTo.toLocalDate());

            for (BackfillPeriod effective : computeEffectiveRanges(chunkFrom, chunkTo)) {
                ZonedDateTime effectiveFrom = effective.from().atStartOfDay(ZoneOffset.UTC);
                ZonedDateTime effectiveTo = effective.to().atStartOfDay(ZoneOffset.UTC);
                stored += downloadChunkYfinance(vixTicker, effectiveFrom, effectiveTo, origin);
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

    boolean isChunkInAnyPeriod(ZonedDateTime chunkFrom, ZonedDateTime chunkTo) {
        if (periods.isEmpty()) return true;
        LocalDate from = chunkFrom.toLocalDate();
        LocalDate to = chunkTo.toLocalDate();
        return periods.stream()
                .anyMatch(p -> !from.isAfter(p.to()) && !to.isBefore(p.from()));
    }

    /**
     * @return true when the [chunkFrom, chunkTo) range intersects the dynamic live-tail period.
     * False when no live-tail is configured (e.g. periods is empty or last period covers today).
     */
    boolean isChunkInLiveTail(ZonedDateTime chunkFrom, ZonedDateTime chunkTo) {
        if (liveTailPeriod == null) return false;
        LocalDate from = chunkFrom.toLocalDate();
        LocalDate to = chunkTo.toLocalDate();
        return !from.isAfter(liveTailPeriod.to()) && !to.isBefore(liveTailPeriod.from());
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
