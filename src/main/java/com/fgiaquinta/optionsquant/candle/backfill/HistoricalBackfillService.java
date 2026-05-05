package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Downloads historical candles from TWS for all configured tickers and timeframes.
 *
 * <p>Activated only when the {@code --backfill} flag is present in the command-line arguments.
 * Resumes from the last successful checkpoint and stores candles in the SQLite repository.
 *
 * <p>This is a foreground CLI runner — it runs once and lets the application proceed normally.
 * The TWS connection is shared with live trading (via {@link IbkrService}).
 */
@Slf4j
@Component
@Order(2)
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class HistoricalBackfillService implements ApplicationRunner {

    private static final long THROTTLE_LOG_THRESHOLD_MS = 1_000;

    private final CandleRepository repository;
    private final BackfillCheckpoint checkpoint;
    private final IbkrService ibkrService;
    private final RateLimiter rateLimiter;
    private final List<String> tickers;
    private final ZonedDateTime backfillStart;

    /**
     * Spring-managed constructor: wires dependencies and builds a {@link RateLimiter}
     * from the configured rate.
     */
    @Autowired
    public HistoricalBackfillService(
            CandleRepository repository,
            BackfillCheckpoint checkpoint,
            IbkrService ibkrService,
            @Value("${ibkr.tickers:#{T(java.util.Collections).emptyList()}}") List<String> tickers,
            @Value("${candles.backfill.start-year:2018}") int startYear,
            @Value("${candles.backfill.rate-per-second:0.1}") double ratePerSecond) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = RateLimiter.create(ratePerSecond);
        this.tickers = tickers;
        this.backfillStart = ZonedDateTime.of(startYear, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        log.info("HistoricalBackfillService initialized: {} tickers, rate={} req/s, startYear={}",
                tickers.size(), ratePerSecond, startYear);
    }

    /**
     * Package-private constructor for unit tests — allows injecting a pre-built {@link RateLimiter}
     * and a custom ticker list without Spring DI.
     */
    HistoricalBackfillService(
            CandleRepository repository,
            BackfillCheckpoint checkpoint,
            IbkrService ibkrService,
            RateLimiter rateLimiter,
            List<String> tickers,
            ZonedDateTime backfillStart) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = rateLimiter;
        this.tickers = tickers;
        this.backfillStart = backfillStart;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill")) {
            log.debug("--backfill flag not present. Historical backfill skipped.");
            return;
        }

        log.info("=== Historical Backfill START — {} tickers, from {} ===",
                tickers.size(), backfillStart.toLocalDate());

        long wallStart = System.currentTimeMillis();
        int totalCandles = 0;
        int tickersDone = 0;

        for (String ticker : tickers) {
            int candlesForTicker = 0;
            for (TimeFrame tf : TimeFrame.values()) {
                candlesForTicker += backfillTickerTimeframe(ticker, tf);
            }
            totalCandles += candlesForTicker;
            tickersDone++;
            log.info("  [backfill] {} complete — {} candles (ticker {}/{})",
                    ticker, candlesForTicker, tickersDone, tickers.size());
        }

        long elapsed = System.currentTimeMillis() - wallStart;
        log.info("=== Historical Backfill DONE — {} tickers, {} candles total, {}s ===",
                tickersDone, totalCandles, elapsed / 1000);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private int backfillTickerTimeframe(String ticker, TimeFrame tf) {
        int stored = 0;

        ZonedDateTime chunkSize = chunkEnd(backfillStart, tf);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        Optional<ZonedDateTime> lastDone = checkpoint.getLastDownloaded(ticker, tf);
        ZonedDateTime chunkFrom = lastDone.orElse(backfillStart);

        while (chunkFrom.isBefore(now)) {
            ZonedDateTime chunkTo = chunkFrom.plus(chunkDuration(tf));
            if (chunkTo.isAfter(now)) {
                chunkTo = now;
            }

            stored += downloadChunk(ticker, tf, chunkFrom, chunkTo);

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

        try {
            log.debug("  [backfill] Downloading {} [{}] chunk {} → {}", ticker, tf,
                    from.toLocalDate(), to.toLocalDate());

            List<Candle> candles = ibkrService.downloadHistoricalData(ticker, tf, to);

            if (!candles.isEmpty()) {
                repository.upsert(ticker, tf, candles);
            }

            checkpoint.save(ticker, tf, to);

            log.debug("  [backfill] {} [{}] chunk {} done — {} candles",
                    ticker, tf, from.toLocalDate(), candles.size());

            return candles.size();

        } catch (Exception e) {
            log.error("  [backfill] ERROR downloading {} [{}] chunk {}: {} — skipping chunk",
                    ticker, tf, from.toLocalDate(), e.getMessage());
            return 0;
        }
    }

    /**
     * Returns the chunk duration for the given timeframe, following the spec:
     * <ul>
     *   <li>5 MIN → 1 month</li>
     *   <li>15 MIN → 1 quarter (3 months)</li>
     *   <li>1 HOUR → 1 quarter (3 months)</li>
     *   <li>1 DAY → 1 year</li>
     * </ul>
     */
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
