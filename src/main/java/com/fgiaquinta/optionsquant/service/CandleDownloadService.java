package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.controller.CandleApiResponses;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates candle download and persistence.
 * Separates business logic from HTTP concerns (controller).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandleDownloadService {

    private final IbkrService ibkrService;
    private final CandleCsvService csvService;
    private final MetricsService metrics;
    private final IbkrProperties ibkrProperties;

    public CandleApiResponses.DownloadResponse downloadAndSave(String ticker, TimeFrame timeframe, boolean saveToCsv) {
        log.info(">>> downloadAndSave(ticker={}, timeframe={}, saveToCsv={})", ticker, timeframe, saveToCsv);
        long startTime = System.currentTimeMillis();

        List<Candle> candles = ibkrService.downloadHistoricalData(ticker, timeframe);
        boolean saved = false;

        if (saveToCsv && !candles.isEmpty()) {
            csvService.saveToCsv(ticker, timeframe, candles);
            saved = true;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< downloadAndSave(ticker={}, timeframe={}) - {} candles, saved={}, {}ms",
                ticker, timeframe, candles.size(), saved, elapsed);

        return new CandleApiResponses.DownloadResponse(ticker, timeframe.name(), candles.size(), saved);
    }

    public CandleApiResponses.DownloadAllResponse downloadAllTimeframesAndSave(String ticker, boolean saveToCsv) {
        log.info(">>> downloadAllTimeframesAndSave(ticker={}, saveToCsv={})", ticker, saveToCsv);
        long startTime = System.currentTimeMillis();

        Map<TimeFrame, List<Candle>> results = ibkrService.downloadAllTimeframes(ticker);

        if (saveToCsv) {
            results.forEach((tf, candles) -> {
                if (!candles.isEmpty()) {
                    csvService.saveToCsv(ticker, tf, candles);
                }
            });
        }

        Map<String, Integer> summary = results.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> e.getValue().size()
                ));

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< downloadAllTimeframesAndSave(ticker={}) - {} timeframes, {}ms",
                ticker, summary.size(), elapsed);

        return new CandleApiResponses.DownloadAllResponse(ticker, summary);
    }

    public CandleApiResponses.DownloadAllTickersResponse downloadAllTickersAndSave(boolean saveToCsv) {
        log.info(">>> downloadAllTickersAndSave(saveToCsv={}) - {} tickers configured", ibkrProperties.tickers().size());
        long startTime = System.currentTimeMillis();

        List<String> tickers = ibkrProperties.tickers();
        Map<String, CandleApiResponses.DownloadAllResponse> results = new java.util.LinkedHashMap<>();
        Map<String, String> errors = new java.util.LinkedHashMap<>();

        for (String ticker : tickers) {
            try {
                log.info("Processing ticker: {}", ticker);
                CandleApiResponses.DownloadAllResponse response = downloadAllTimeframesAndSave(ticker, saveToCsv);
                results.put(ticker, response);
            } catch (Exception e) {
                log.error("Failed to download ticker {}: {}", ticker, e.getMessage(), e);
                errors.put(ticker, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< downloadAllTickersAndSave - {}/{} tickers successful, {}ms",
                results.size(), tickers.size(), elapsed);

        return new CandleApiResponses.DownloadAllTickersResponse(
                tickers.size(),
                results.size(),
                errors.size(),
                results,
                errors
        );
    }
}
