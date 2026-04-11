package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.TickerInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Loads and manages ticker information from CSV file.
 * Provides fundamental analysis data for each ticker.
 *
 * CSV format: ticker,[active],company,sector,market_cap_b,pe_ratio,dividend_yield,beta,avg_volume_m,description
 * The 'active' column is optional. If present, filters out inactive tickers.
 * If absent, all tickers are considered active.
 */
@Slf4j
@Service
public class TickerService {

    private static final String TICKERS_CSV_PATH = "data/tickers.csv";
    private final Map<String, TickerInfo> tickerMap = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;
    private boolean hasActiveColumn = false;

    /**
     * Loads tickers from CSV file on startup.
     */
    public void loadTickers() {
        if (loaded) {
            log.debug("Tickers already loaded");
            return;
        }

        Path csvPath = Path.of(TICKERS_CSV_PATH);
        if (!Files.exists(csvPath)) {
            log.warn("Tickers CSV not found at {}. Using empty list.", csvPath.toAbsolutePath());
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String headerLine = reader.readLine(); // Skip header
            if (headerLine == null) {
                log.warn("Empty tickers CSV file");
                return;
            }

            int loadedCount = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    TickerInfo info = parseLine(line);
                    if (info != null) {
                        tickerMap.put(info.ticker(), info);
                        loadedCount++;
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse ticker line: {} - {}", line, e.getMessage());
                }
            }

            loaded = true;
            log.info("✅ Loaded {} tickers from CSV with fundamental data", loadedCount);
        } catch (IOException e) {
            log.error("Failed to load tickers from CSV: {}", e.getMessage());
        }
    }

    /**
     * Gets ticker info for a specific symbol.
     */
    public Optional<TickerInfo> getTickerInfo(String ticker) {
        if (!loaded) loadTickers();
        return Optional.ofNullable(tickerMap.get(ticker.toUpperCase()));
    }

    /**
     * Gets all loaded tickers.
     */
    public List<TickerInfo> getAllTickers() {
        if (!loaded) loadTickers();
        return new ArrayList<>(tickerMap.values());
    }

    /**
     * Gets just the ticker symbols (for backwards compatibility).
     */
    public List<String> getTickerSymbols() {
        if (!loaded) loadTickers();
        return tickerMap.keySet().stream().sorted().collect(Collectors.toList());
    }

    /**
     * Filters tickers by sector.
     */
    public List<TickerInfo> getBySector(String sector) {
        if (!loaded) loadTickers();
        return tickerMap.values().stream()
                .filter(t -> t.sector() != null && t.sector().equalsIgnoreCase(sector))
                .collect(Collectors.toList());
    }

    /**
     * Gets high-quality tickers based on fundamental criteria.
     */
    public List<TickerInfo> getHighQualityTickers() {
        if (!loaded) loadTickers();
        return tickerMap.values().stream()
                .filter(t -> t.peRatio() != null && t.peRatio() > 0 && t.peRatio() < 30)
                .filter(t -> t.roic() != null && t.roic() > 15)
                .filter(t -> t.debtToEquity() != null && t.debtToEquity() < 1.0)
                .collect(Collectors.toList());
    }

    /**
     * Gets hot tickers (high-priority tickers for scanning).
     * Returns top 15 tickers by market cap and liquidity.
     */
    public List<String> getHotTickers() {
        if (!loaded) loadTickers();
        return tickerMap.values().stream()
                .filter(t -> t.marketCapBillion() != null && t.marketCapBillion() > 50)
                .sorted((a, b) -> Long.compare(b.marketCapBillion(), a.marketCapBillion()))
                .limit(15)
                .map(TickerInfo::ticker)
                .collect(Collectors.toList());
    }

    /**
     * Gets tickers by market cap range.
     */
    public List<TickerInfo> getByMarketCapRange(long minCapB, long maxCapB) {
        if (!loaded) loadTickers();
        return tickerMap.values().stream()
                .filter(t -> t.marketCapBillion() != null)
                .filter(t -> t.marketCapBillion() >= minCapB && t.marketCapBillion() <= maxCapB)
                .collect(Collectors.toList());
    }

    /**
     * Gets tickers with high earnings growth.
     */
    public List<TickerInfo> getHighGrowthTickers(double minEpsGrowth) {
        if (!loaded) loadTickers();
        return tickerMap.values().stream()
                .filter(t -> t.epsGrowth() != null && t.epsGrowth() >= minEpsGrowth)
                .collect(Collectors.toList());
    }

    /**
     * Parses a CSV line into TickerInfo.
     * Supports both formats:
     * - Old: ticker,company,sector,...
     * - New: ticker,active,company,sector,...
     */
    private TickerInfo parseLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 10) return null;

        int offset = 0;
        boolean active = true;  // Default to active

        // Check if second column is active flag (true/false)
        if (parts.length >= 11 && ("true".equalsIgnoreCase(parts[1]) || "false".equalsIgnoreCase(parts[1]))) {
            active = Boolean.parseBoolean(parts[1]);
            offset = 1;
        }

        if (!active) {
            return null;  // Skip inactive tickers
        }

        try {
            return new TickerInfo(
                    parts[0].trim(),                                    // ticker
                    parts[1 + offset].trim(),                           // company_name
                    parseString(parts[2 + offset]),                     // sector
                    parseLong(parts[3 + offset]),                       // market_cap_billion
                    parseDouble(parts[4 + offset]),                     // pe_ratio
                    parseDouble(parts[5 + offset]),                     // dividend_yield
                    parseDouble(parts[6 + offset]),                     // beta
                    parseDouble(parts[7 + offset]),                     // eps_growth
                    parseDouble(parts[8 + offset]),                     // revenue_growth
                    parseDouble(parts[9 + offset]),                     // debt_to_equity
                    parseDouble(parts[10 + offset]),                    // roic
                    parts.length > 11 + offset ? parts[11 + offset].trim() : null // notes
            );
        } catch (Exception e) {
            log.debug("Failed to parse line: {}", e.getMessage());
            return null;
        }
    }

    private String parseString(String s) {
        return (s == null || s.trim().isEmpty() || "N/A".equalsIgnoreCase(s.trim())) ? null : s.trim();
    }

    private Long parseLong(String s) {
        try {
            String cleaned = s.replace("B", "").trim();
            return (cleaned == null || cleaned.isEmpty() || "N/A".equalsIgnoreCase(cleaned)) 
                    ? null : Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String s) {
        try {
            return (s == null || s.trim().isEmpty() || "N/A".equalsIgnoreCase(s.trim())) 
                    ? null : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets count of loaded tickers.
     */
    public int getLoadedCount() {
        if (!loaded) loadTickers();
        return tickerMap.size();
    }

    public boolean isLoaded() {
        return loaded;
    }
}
