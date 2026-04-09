package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import com.fgiaquinta.optionsquant.service.IbkrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Downloads historical data on startup when enabled.
 * Activate with: --download-on-start=true
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "download-on-start", havingValue = "true")
public class CandleDownloader implements CommandLineRunner {

    private static final int DELAY_BETWEEN_TICKERS_MS = 2000;

    private final IbkrService ibkrService;
    private final CandleCsvService csvService;
    private final IbkrProperties properties;

    @Override
    public void run(String... args) {
        log.info("=== STARTING CANDLE DOWNLOAD ===");
        log.info("Tickers: {}", properties.tickers());

        ibkrService.connect();
        try {
            downloadAllTickers(properties.tickers());
            log.info("=== CANDLE DOWNLOAD COMPLETE ===");
        } finally {
            ibkrService.disconnect();
        }
    }

    private void downloadAllTickers(List<String> tickers) {
        for (int i = 0; i < tickers.size(); i++) {
            downloadSingleTicker(tickers.get(i), i + 1, tickers.size());
            if (i < tickers.size() - 1) {
                sleep(DELAY_BETWEEN_TICKERS_MS);
            }
        }
    }

    private void downloadSingleTicker(String ticker, int current, int total) {
        log.info("[{}/{}] Processing {}", current, total, ticker);
        logLocalDataStatus(ticker);

        Map<TimeFrame, List<Candle>> results = ibkrService.downloadAllTimeframes(ticker);
        saveResults(ticker, results);
    }

    private void logLocalDataStatus(String ticker) {
        List<Candle> existingData = csvService.loadFromCsv(ticker, TimeFrame.DAY_1);
        String status = existingData.isEmpty() ? "NO" : "YES (" + existingData.size() + " candles)";
        log.info("  Local DAY_1 data: {}", status);
    }

    private void saveResults(String ticker, Map<TimeFrame, List<Candle>> results) {
        for (var entry : results.entrySet()) {
            TimeFrame tf = entry.getKey();
            List<Candle> candles = entry.getValue();

            if (!candles.isEmpty()) {
                csvService.saveToCsv(ticker, tf, candles);
                log.info("  {} saved: {} candles", tf.name(), candles.size());
            } else {
                log.warn("  {} FAILED - no data received", tf.name());
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
