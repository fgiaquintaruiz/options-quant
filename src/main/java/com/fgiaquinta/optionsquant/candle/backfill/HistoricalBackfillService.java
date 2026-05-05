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
    private final YfinanceHistoricalClient yfinanceClient;
    private final List<String> vixTickers;
    private final int yfinanceCutoffYears;
    private final boolean yfinanceEnabled;

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
            @Value("${candles.backfill.yfinance.cutoff-years:5}") int yfinanceCutoffYears,
            @Value("${candles.backfill.yfinance.enabled:true}") boolean yfinanceEnabled) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = RateLimiter.create(ratePerSecond);
        this.tickers = tickers;
        this.backfillStart = ZonedDateTime.of(startYear, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        this.yfinanceClient = yfinanceClient;
        this.vixTickers = vixTickers;
        this.yfinanceCutoffYears = yfinanceCutoffYears;
        this.yfinanceEnabled = yfinanceEnabled;

        log.info("HistoricalBackfillService initialized: {} tickers, rate={} req/s, startYear={}",
                tickers.size(), ratePerSecond, startYear);
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
            int yfinanceCutoffYears,
            boolean yfinanceEnabled) {

        this.repository = repository;
        this.checkpoint = checkpoint;
        this.ibkrService = ibkrService;
        this.rateLimiter = rateLimiter;
        this.tickers = tickers;
        this.backfillStart = backfillStart;
        this.yfinanceClient = yfinanceClient;
        this.vixTickers = vixTickers;
        this.yfinanceCutoffYears = yfinanceCutoffYears;
        this.yfinanceEnabled = yfinanceEnabled;
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

        for (String vixTicker : vixTickers) {
            int candlesForVix = backfillTickerYfinanceOnly(vixTicker);
            log.info("  [backfill] {} (VIX) complete — {} candles", vixTicker, candlesForVix);
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

        if (tf != TimeFrame.DAY_1) {
            return downloadChunkTwsOnly(ticker, tf, from, to);
        }

        ZonedDateTime cutoff = ZonedDateTime.now(ZoneOffset.UTC).minusYears(yfinanceCutoffYears);
        boolean preempt = yfinanceEnabled && yfinanceClient != null && to.isBefore(cutoff);

        if (preempt) {
            return downloadChunkYfinance(ticker, from, to);
        }

        return downloadChunkTwsWithFallback(ticker, tf, from, to);
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

    private int downloadChunkTwsWithFallback(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to) {
        List<Candle> candles = null;
        try {
            candles = ibkrService.downloadHistoricalData(ticker, tf, to);
        } catch (Exception e) {
            log.warn("  [backfill] TWS failed for {} [{}] {}, trying yfinance: {}", ticker, tf, from.toLocalDate(), e.getMessage());
        }

        if (candles == null || candles.isEmpty()) {
            if (yfinanceEnabled && yfinanceClient != null) {
                return downloadChunkYfinance(ticker, from, to);
            }
            if (candles != null) {
                checkpoint.save(ticker, tf, to, BackfillStatus.COMPLETE_EMPTY);
            }
            return 0;
        }

        repository.upsert(ticker, tf, candles);
        checkpoint.save(ticker, tf, to, BackfillStatus.COMPLETE_TWS);
        return candles.size();
    }

    private int downloadChunkYfinance(String ticker, ZonedDateTime from, ZonedDateTime to) {
        List<Candle> candles = yfinanceClient.fetchDailyCandles(ticker, from.toLocalDate(), to.toLocalDate());
        if (!candles.isEmpty()) repository.upsert(ticker, TimeFrame.DAY_1, candles);
        checkpoint.save(ticker, TimeFrame.DAY_1, to,
                candles.isEmpty() ? BackfillStatus.COMPLETE_EMPTY : BackfillStatus.COMPLETE_YFINANCE);
        log.debug("  [backfill] yfinance {} {} → {} — {} candles",
                ticker, from.toLocalDate(), to.toLocalDate(), candles.size());
        return candles.size();
    }

    private int backfillTickerYfinanceOnly(String vixTicker) {
        int stored = 0;
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Optional<ZonedDateTime> lastDone = checkpoint.getLastDownloaded(vixTicker, TimeFrame.DAY_1);
        ZonedDateTime chunkFrom = lastDone.orElse(backfillStart);
        while (chunkFrom.isBefore(now)) {
            ZonedDateTime chunkTo = chunkFrom.plus(chunkDuration(TimeFrame.DAY_1));
            if (chunkTo.isAfter(now)) chunkTo = now;
            stored += downloadChunkYfinance(vixTicker, chunkFrom, chunkTo);
            chunkFrom = chunkTo;
        }
        return stored;
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
