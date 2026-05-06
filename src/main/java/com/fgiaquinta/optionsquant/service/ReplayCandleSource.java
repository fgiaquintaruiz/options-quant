package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Replay data source for live-replay-mode. Loads candles from CSV for the
 * requested replay date, backfilling from IBKR when the CSV is stale
 * (same pattern as {@link StrategyScannerService#downloadTimeframeDelta}).
 *
 * After preload, {@link #getCandlesUntil} returns only candles with
 * timestamp &lt;= virtualNow — used by the scanner to simulate a live feed.
 */
@Slf4j
@Service
public class ReplayCandleSource {

    public static class MissingDataException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public MissingDataException(String message) { super(message); }
    }

    private final CandleRepository candleRepository;
    private final IbkrService ibkrService;

    // ticker -> timeframe -> (timestamp -> candle)
    private final Map<String, Map<TimeFrame, NavigableMap<ZonedDateTime, Candle>>> cache = new ConcurrentHashMap<>();

    public ReplayCandleSource(CandleRepository candleRepository, IbkrService ibkrService) {
        this.candleRepository = candleRepository;
        this.ibkrService = ibkrService;
    }

    /**
     * Load candles for each (ticker, timeframe) into the cache, backfilling
     * from IBKR when the CSV does not cover the target date. Throws
     * {@link MissingDataException} listing tickers without usable data when
     * IBKR is unreachable.
     */
    public void preload(LocalDate date, Set<String> tickers, List<TimeFrame> timeframes) {
        cache.clear();
        List<String> missing = new ArrayList<>();

        for (String ticker : tickers) {
            Map<TimeFrame, NavigableMap<ZonedDateTime, Candle>> perTf = new ConcurrentHashMap<>();

            for (TimeFrame tf : timeframes) {
                List<Candle> candles = candleRepository.load(ticker, tf);

                if (!coversDate(candles, date)) {
                    if (!ibkrService.isConnected()) {
                        missing.add(ticker + " [" + tf + "]");
                        continue;
                    }
                    List<Candle> fresh = ibkrService.downloadHistoricalData(ticker, tf);
                    candles = merge(candles, fresh);
                    candleRepository.upsert(ticker, tf, candles);
                }

                NavigableMap<ZonedDateTime, Candle> indexed = new TreeMap<>();
                for (Candle c : candles) indexed.put(c.timestamp(), c);
                perTf.put(tf, indexed);
            }

            cache.put(ticker, perTf);
        }

        if (!missing.isEmpty()) {
            throw new MissingDataException(
                "Missing candles for replay date " + date + " and TWS is not connected. " +
                "Missing: " + missing.stream().collect(Collectors.joining(", ")));
        }
    }

    public List<Candle> getCandlesUntil(String ticker, TimeFrame tf, ZonedDateTime virtualNow) {
        Map<TimeFrame, NavigableMap<ZonedDateTime, Candle>> byTf = cache.get(ticker);
        if (byTf == null) return Collections.emptyList();
        NavigableMap<ZonedDateTime, Candle> index = byTf.get(tf);
        if (index == null) return Collections.emptyList();
        return new ArrayList<>(index.headMap(virtualNow, true).values());
    }

    public void clear() {
        cache.clear();
    }

    private static boolean coversDate(List<Candle> candles, LocalDate date) {
        if (candles == null || candles.isEmpty()) return false;
        for (Candle c : candles) {
            if (c.timestamp().toLocalDate().equals(date)) return true;
        }
        return false;
    }

    private static List<Candle> merge(List<Candle> existing, List<Candle> fresh) {
        NavigableMap<ZonedDateTime, Candle> merged = new TreeMap<>();
        if (existing != null) for (Candle c : existing) merged.put(c.timestamp(), c);
        if (fresh != null) for (Candle c : fresh) merged.put(c.timestamp(), c);
        return new ArrayList<>(merged.values());
    }
}
