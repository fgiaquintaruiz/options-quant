package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.dto.TickerFundamentalPayload;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Loads tickers from CSV, merges optional {@code data/ticker-runtime.json} (ABM),
 * and resolves universe / HOT order from runtime file → YAML → CSV defaults.
 */
@Slf4j
@Service
public class TickerService {

    private static final String TICKERS_CSV_PATH = "data/tickers.csv";

    private final IbkrProperties ibkrProperties;
    private final TickerRuntimeConfigStore runtimeConfigStore;

    /** Raw symbols from CSV + merged fundamentals (may be larger than active universe). */
    private final Map<String, TickerInfo> tickerMap = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;
    private boolean hasActiveColumn = false;

    public TickerService(IbkrProperties ibkrProperties, TickerRuntimeConfigStore runtimeConfigStore) {
        this.ibkrProperties = ibkrProperties;
        this.runtimeConfigStore = runtimeConfigStore;
    }

    /**
     * Reloads CSV + runtime config (call after saving ABM / ticker-runtime.json).
     */
    public synchronized void reload() {
        loaded = false;
        tickerMap.clear();
        loadTickers();
    }

    public void loadTickers() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) return;

            Path csvPath = Path.of(TICKERS_CSV_PATH);
            if (!Files.exists(csvPath)) {
                log.warn("Tickers CSV not found at {}.", csvPath.toAbsolutePath());
            } else {
                try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
                    String headerLine = reader.readLine();
                    if (headerLine == null) {
                        log.warn("Empty tickers CSV file");
                    } else {
                        int loadedCount = 0;
                        String line;
                        while ((line = reader.readLine()) != null) {
                            try {
                                TickerInfo info = parseLine(line);
                                if (info != null) {
                                    tickerMap.put(info.ticker().toUpperCase(Locale.ROOT), info);
                                    loadedCount++;
                                }
                            } catch (Exception e) {
                                log.debug("Failed to parse ticker line: {} - {}", line, e.getMessage());
                            }
                        }
                        log.info("Loaded {} tickers from CSV", loadedCount);
                    }
                } catch (IOException e) {
                    log.error("Failed to load tickers from CSV: {}", e.getMessage());
                }
            }

            Optional<TickerRuntimeConfigPayload> rt = runtimeConfigStore.load();
            rt.ifPresent(payload -> mergeRuntimeFundamentals(payload.fundamentals()));

            ensureUniverseStubs(resolveUniverseSymbols(rt.orElse(null)));

            loaded = true;
            log.info("Ticker universe: {} symbols active", getTickerSymbols().size());
        }
    }

    private void mergeRuntimeFundamentals(Map<String, TickerFundamentalPayload> fundamentals) {
        if (fundamentals == null || fundamentals.isEmpty()) return;
        for (Map.Entry<String, TickerFundamentalPayload> e : fundamentals.entrySet()) {
            String sym = e.getKey() == null ? "" : e.getKey().trim().toUpperCase(Locale.ROOT);
            if (sym.isEmpty() || e.getValue() == null) continue;
            TickerInfo merged = e.getValue().toTickerInfo(sym);
            tickerMap.put(sym, merged);
        }
    }

    private List<String> resolveUniverseSymbols(TickerRuntimeConfigPayload runtime) {
        if (runtime != null && !runtime.universe().isEmpty()) {
            return normalizeSymbolList(runtime.universe());
        }
        if (ibkrProperties.universeTickers() != null && !ibkrProperties.universeTickers().isEmpty()) {
            return normalizeSymbolList(ibkrProperties.universeTickers());
        }
        return tickerMap.keySet().stream().sorted().collect(Collectors.toList());
    }

    private void ensureUniverseStubs(List<String> universe) {
        for (String sym : universe) {
            tickerMap.computeIfAbsent(sym, TickerService::minimalStub);
        }
    }

    private static TickerInfo minimalStub(String sym) {
        return new TickerInfo(sym, sym, "Unknown", null, null, null, null, null, null, null, null,
                "Sin datos fundamentales en CSV; completar en Config o ticker-runtime.json");
    }

    private static List<String> normalizeSymbolList(List<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String u = s.trim().toUpperCase(Locale.ROOT);
            if (!u.isEmpty()) out.add(u);
        }
        return new ArrayList<>(out);
    }

    public Optional<TickerInfo> getTickerInfo(String ticker) {
        if (!loaded) loadTickers();
        if (ticker == null) return Optional.empty();
        return Optional.ofNullable(tickerMap.get(ticker.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * Active trading universe (ALL scope): runtime → YAML {@code universe-tickers} → all CSV symbols.
     */
    public List<String> getTickerSymbols() {
        if (!loaded) loadTickers();
        Optional<TickerRuntimeConfigPayload> rt = runtimeConfigStore.load();
        if (rt.isPresent() && !rt.get().universe().isEmpty()) {
            return normalizeSymbolList(rt.get().universe());
        }
        if (ibkrProperties.universeTickers() != null && !ibkrProperties.universeTickers().isEmpty()) {
            return normalizeSymbolList(ibkrProperties.universeTickers());
        }
        return tickerMap.keySet().stream().sorted().collect(Collectors.toList());
    }

    /**
     * HOT list: processed first (config order).
     *
     * <p>Guard semantics (runtime-wins with explicit-empty support):
     * <ol>
     *   <li>Runtime file present AND {@code hot != null} (including {@code []}) → return runtime list
     *       filtered to universe. An explicit empty list is a valid override — no YAML fallback.</li>
     *   <li>Runtime file absent OR {@code hot == null} → fall back to YAML {@code hot-tickers}
     *       → then top market cap from CSV as last resort. Logs INFO once on YAML fallback.</li>
     * </ol>
     */
    public List<String> getHotTickers() {
        if (!loaded) loadTickers();
        List<String> universe = getTickerSymbols();
        Set<String> universeSet = new LinkedHashSet<>(universe);

        Optional<TickerRuntimeConfigPayload> rt = runtimeConfigStore.load();

        // HOT guard: if runtime file is present AND hot field was explicitly provided (non-null),
        // return the runtime list (possibly empty) — never fall back to YAML in this branch.
        if (rt.isPresent() && rt.get().hot() != null) {
            List<String> runtimeHot = rt.get().hot();
            List<String> ordered = new ArrayList<>();
            for (String h : runtimeHot) {
                if (h == null) continue;
                String u = h.trim().toUpperCase(Locale.ROOT);
                if (universeSet.contains(u)) {
                    ordered.add(u);
                }
            }
            return ordered;
        }

        // YAML fallback — runtime absent or hot field explicitly null
        log.info("HOT bootstrapped from YAML; create runtime to override");
        List<String> configured = null;
        if (ibkrProperties.hotTickers() != null && !ibkrProperties.hotTickers().isEmpty()) {
            configured = ibkrProperties.hotTickers();
        }

        if (configured != null && !configured.isEmpty()) {
            List<String> ordered = new ArrayList<>();
            for (String h : configured) {
                if (h == null) continue;
                String u = h.trim().toUpperCase(Locale.ROOT);
                if (universeSet.contains(u)) {
                    ordered.add(u);
                }
            }
            return ordered;
        }

        int effectiveCount = rt.isPresent() && rt.get().hotTickerCount() != null
                ? rt.get().hotTickerCount()
                : ibkrProperties.hotTickerCount();
        return tickerMap.values().stream()
                .filter(t -> t.marketCapBillion() != null && t.marketCapBillion() > 50)
                .filter(t -> universeSet.contains(t.ticker()))
                .sorted((a, b) -> Long.compare(b.marketCapBillion(), a.marketCapBillion()))
                .limit(effectiveCount)
                .map(TickerInfo::ticker)
                .collect(Collectors.toList());
    }

    /**
     * HOT symbols in config order for scanners (intersection with {@code allTickers} list).
     */
    public List<String> orderHotForScan(List<String> allTickers, List<String> hotConfig) {
        if (allTickers == null || allTickers.isEmpty()) return List.of();
        Set<String> all = new LinkedHashSet<>();
        for (String t : allTickers) {
            if (t != null) all.add(t.trim().toUpperCase(Locale.ROOT));
        }
        List<String> out = new ArrayList<>();
        if (hotConfig != null) {
            for (String h : hotConfig) {
                if (h == null) continue;
                String u = h.trim().toUpperCase(Locale.ROOT);
                if (all.contains(u)) out.add(u);
            }
        }
        return out;
    }

    public List<TickerInfo> getAllTickers() {
        if (!loaded) loadTickers();
        List<String> u = getTickerSymbols();
        List<TickerInfo> list = new ArrayList<>();
        for (String sym : u) {
            TickerInfo info = tickerMap.get(sym);
            if (info != null) list.add(info);
        }
        return list;
    }

    public List<TickerInfo> getBySector(String sector) {
        return getAllTickers().stream()
                .filter(t -> t.sector() != null && t.sector().equalsIgnoreCase(sector))
                .collect(Collectors.toList());
    }

    public List<TickerInfo> getHighQualityTickers() {
        return getAllTickers().stream()
                .filter(t -> t.peRatio() != null && t.peRatio() > 0 && t.peRatio() < 30)
                .filter(t -> t.roic() != null && t.roic() > 15)
                .filter(t -> t.debtToEquity() != null && t.debtToEquity() < 1.0)
                .collect(Collectors.toList());
    }

    public List<TickerInfo> getByMarketCapRange(long minCapB, long maxCapB) {
        return getAllTickers().stream()
                .filter(t -> t.marketCapBillion() != null)
                .filter(t -> t.marketCapBillion() >= minCapB && t.marketCapBillion() <= maxCapB)
                .collect(Collectors.toList());
    }

    public List<TickerInfo> getHighGrowthTickers(double minEpsGrowth) {
        return getAllTickers().stream()
                .filter(t -> t.epsGrowth() != null && t.epsGrowth() >= minEpsGrowth)
                .collect(Collectors.toList());
    }

    private TickerInfo parseLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 10) return null;

        int offset = 0;
        boolean active = true;

        if (parts.length >= 11 && ("true".equalsIgnoreCase(parts[1]) || "false".equalsIgnoreCase(parts[1]))) {
            active = Boolean.parseBoolean(parts[1]);
            offset = 1;
        }

        if (!active) {
            return null;
        }

        try {
            return new TickerInfo(
                    parts[0].trim().toUpperCase(Locale.ROOT),
                    parts[1 + offset].trim(),
                    parseString(parts[2 + offset]),
                    parseLong(parts[3 + offset]),
                    parseDouble(parts[4 + offset]),
                    parseDouble(parts[5 + offset]),
                    parseDouble(parts[6 + offset]),
                    parseDouble(parts[7 + offset]),
                    parseDouble(parts[8 + offset]),
                    parseDouble(parts[9 + offset]),
                    parseDouble(parts[10 + offset]),
                    parts.length > 11 + offset ? parts[11 + offset].trim() : null
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

    public int getLoadedCount() {
        if (!loaded) loadTickers();
        return getTickerSymbols().size();
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Map<String, TickerInfo> snapshotMapForConfigApi() {
        if (!loaded) loadTickers();
        LinkedHashMap<String, TickerInfo> snap = new LinkedHashMap<>();
        for (String sym : getTickerSymbols()) {
            snap.put(sym, tickerMap.getOrDefault(sym, minimalStub(sym)));
        }
        return snap;
    }
}
