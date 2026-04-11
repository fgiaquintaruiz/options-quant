package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service for saving and loading candle data from CSV files.
 * Throws UncheckedIOException on IO failures instead of silently swallowing.
 */
@Slf4j
@Service
public class CandleCsvService {

    // Separate logger for CSV file operations
    private static final org.slf4j.Logger csvLog =
            org.slf4j.LoggerFactory.getLogger("CsvOperations");

    private static final DateTimeFormatter CSV_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path DEFAULT_DATA_DIR = Path.of("data");

    private Path dataDir = DEFAULT_DATA_DIR;

    /**
     * For testing with a temp directory.
     */
    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Save candles to CSV file.
     * @throws UncheckedIOException if writing fails
     */
    public void saveToCsv(String ticker, TimeFrame timeframe, List<Candle> candles) {
        log.debug(">>> saveToCsv(ticker={}, timeframe={}) - {} candles", ticker, timeframe, candles != null ? candles.size() : 0);
        long startTime = System.currentTimeMillis();

        if (candles == null || candles.isEmpty()) {
            csvLog.warn("⚠️ [CSV] saveToCsv(ticker={}, timeframe={}) - No candles to save", ticker, timeframe);
            return;
        }

        try {
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
                csvLog.info("📁 [CSV] Created data directory: {}", dataDir.toAbsolutePath());
            }

            String filename = timeframe.toCacheKey(ticker) + ".csv";
            Path filepath = dataDir.resolve(filename);

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filepath))) {
                pw.println("Time,Open,High,Low,Close,Volume");

                for (Candle candle : candles) {
                    String timeStr = candle.timestamp()
                            .withZoneSameInstant(ZoneId.systemDefault())
                            .format(CSV_FORMAT);

                    pw.printf(Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%d%n",
                            timeStr,
                            candle.open(),
                            candle.high(),
                            candle.low(),
                            candle.close(),
                            candle.volume());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            csvLog.info("💾 [CSV] Saved {} candles for {} [{}] → {} in {}ms",
                    candles.size(), ticker, timeframe, filepath.toAbsolutePath(), elapsed);

        } catch (IOException e) {
            log.error("Failed to save CSV for {} [{}]: {}", ticker, timeframe, e.getMessage());
            throw new UncheckedIOException("Failed to save CSV for " + ticker + " [" + timeframe + "]", e);
        }
    }

    /**
     * Load candles from CSV file. Returns empty list if file doesn't exist.
     */
    public List<Candle> loadFromCsv(String ticker, TimeFrame timeframe) {
        log.debug(">>> loadFromCsv(ticker={}, timeframe={})", ticker, timeframe);
        long startTime = System.currentTimeMillis();

        String filename = timeframe.toCacheKey(ticker) + ".csv";
        Path filepath = dataDir.resolve(filename);

        if (!Files.exists(filepath)) {
            log.debug("<<< loadFromCsv(ticker={}, timeframe={}) - No file found", ticker, timeframe);
            return List.of();
        }

        List<Candle> candles = new ArrayList<>();

        try (BufferedReader br = Files.newBufferedReader(filepath)) {
            br.readLine(); // Skip header

            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 6) continue;

                try {
                    java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(parts[0], CSV_FORMAT);
                    java.time.ZonedDateTime timestamp = localDateTime.atZone(ZoneId.systemDefault());

                    double open = Double.parseDouble(parts[1]);
                    double high = Double.parseDouble(parts[2]);
                    double low = Double.parseDouble(parts[3]);
                    double close = Double.parseDouble(parts[4]);
                    long volume = Long.parseLong(parts[5]);

                    candles.add(new Candle(timestamp, open, high, low, close, volume));
                } catch (Exception e) {
                    log.warn("Failed to parse CSV line: {}", line);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            csvLog.debug("📂 [CSV] Loaded {} candles for {} [{}] from {} in {}ms",
                    candles.size(), ticker, timeframe, filepath, elapsed);

        } catch (IOException e) {
            log.error("Failed to load CSV for {} [{}]: {}", ticker, timeframe, e.getMessage());
            throw new UncheckedIOException("Failed to load CSV for " + ticker + " [" + timeframe + "]", e);
        }

        return candles;
    }

    /**
     * Check if CSV file exists for a ticker and timeframe.
     */
    public boolean hasLocalData(String ticker, TimeFrame timeframe) {
        String filename = timeframe.toCacheKey(ticker) + ".csv";
        boolean exists = Files.exists(dataDir.resolve(filename));
        log.debug(">>> hasLocalData(ticker={}, timeframe={}) -> {}", ticker, timeframe, exists);
        return exists;
    }
}
