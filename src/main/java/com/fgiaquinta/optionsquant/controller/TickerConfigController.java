package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.dto.TickerFundamentalPayload;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import com.fgiaquinta.optionsquant.service.TickerRuntimeConfigStore;
import com.fgiaquinta.optionsquant.service.TickerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * ABM for ticker universe, HOT priority, and fundamental overrides (persisted to {@code data/ticker-runtime.json}).
 */
@Slf4j
@RestController
@RequestMapping("/api/ticker-config")
@RequiredArgsConstructor
public class TickerConfigController {

    private final TickerService tickerService;
    private final TickerRuntimeConfigStore runtimeConfigStore;
    private final OrderExecutionService orderExecutionService;
    private final IbkrProperties ibkrProperties;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        tickerService.loadTickers();
        var rt = runtimeConfigStore.load();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("universe", tickerService.getTickerSymbols());
        body.put("hot", rt.isPresent() && rt.get().hot() != null && !rt.get().hot().isEmpty()
                ? rt.get().hot()
                : tickerService.getHotTickers());
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<String, TickerInfo> e : tickerService.snapshotMapForConfigApi().entrySet()) {
            entries.put(e.getKey(), TickerFundamentalPayload.fromTickerInfo(e.getValue()));
        }
        body.put("entries", entries);
        body.put("fundamentals", entries);
        body.put("hasRuntimeFile", rt.isPresent());
        rt.ifPresent(c -> {
            body.put("runtimeUniverseEmpty", c.universe().isEmpty());
            body.put("runtimeHotEmpty", c.hot() == null || c.hot().isEmpty());
        });
        return ResponseEntity.ok(body);
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateTicker(@RequestParam String symbol) {
        log.info(">>> GET /api/ticker-config/validate symbol={}", symbol);
        try {
            boolean valid = orderExecutionService.validateTicker(symbol);
            log.info("<<< GET /api/ticker-config/validate symbol={} valid={}", symbol, valid);
            if (valid) {
                return ResponseEntity.ok(Map.of("valid", true));
            } else {
                return ResponseEntity.ok(Map.of("valid", false, "reason", "Ticker no encontrado en TWS"));
            }
        } catch (IllegalStateException e) {
            log.warn("<<< GET /api/ticker-config/validate - TWS not connected: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("valid", false, "reason", "TWS no conectado — verificá la conexión"));
        } catch (Exception e) {
            log.warn("<<< GET /api/ticker-config/validate - unexpected error for symbol={}: {}", symbol, e.getMessage());
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Error al validar con TWS — intentá de nuevo"));
        }
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> putConfig(@RequestBody TickerRuntimeConfigPayload payload) throws IOException {
        runtimeConfigStore.save(payload);
        tickerService.reload();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "universe", tickerService.getTickerSymbols(),
                "hot", tickerService.getHotTickers()
        ));
    }

    /**
     * Remove one symbol from universe and fundamentals map in runtime file (rewrites file).
     */
    @DeleteMapping("/symbol/{symbol}")
    public ResponseEntity<Map<String, Object>> deleteSymbol(@PathVariable String symbol) throws IOException {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        var current = runtimeConfigStore.load().orElse(new TickerRuntimeConfigPayload(List.of(), List.of(), Map.of(), null));
        List<String> universe = current.universe().stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.equals(sym))
                .toList();
        List<String> currentHot = current.hot() != null ? current.hot() : List.<String>of();
        List<String> hot = currentHot.stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.equals(sym))
                .toList();
        Map<String, TickerFundamentalPayload> fund = new LinkedHashMap<>(current.fundamentals());
        fund.remove(sym);
        TickerRuntimeConfigPayload next = new TickerRuntimeConfigPayload(universe, hot, fund, current.hotTickerCount());
        runtimeConfigStore.save(next);
        tickerService.reload();
        return ResponseEntity.ok(Map.of("success", true, "deleted", sym));
    }

    /**
     * Returns the effective hot-ticker count (runtime override if set, otherwise YAML default).
     * GET /api/ticker-config/hot-ticker-count → { effectiveCount, runtimeOverride, ymlDefault }
     */
    @GetMapping("/hot-ticker-count")
    public ResponseEntity<Map<String, Object>> getHotTickerCount() {
        var rt = runtimeConfigStore.load();
        Integer runtimeOverride = rt.flatMap(p -> Optional.ofNullable(p.hotTickerCount())).orElse(null);
        int ymlDefault = ibkrProperties.hotTickerCount();
        int effectiveCount = runtimeOverride != null ? runtimeOverride : ymlDefault;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveCount", effectiveCount);
        body.put("runtimeOverride", runtimeOverride);
        body.put("ymlDefault", ymlDefault);
        return ResponseEntity.ok(body);
    }

    /**
     * Persists a runtime override for hot-ticker-count (1–100).
     * PUT /api/ticker-config/hot-ticker-count?count=N → { success, effectiveCount }
     * Returns 400 if count is out of range.
     */
    @PutMapping("/hot-ticker-count")
    public ResponseEntity<Map<String, Object>> putHotTickerCount(@RequestParam int count) throws IOException {
        if (count < 1 || count > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "hotTickerCount must be between 1 and 100, got: " + count));
        }
        var current = runtimeConfigStore.load().orElse(new TickerRuntimeConfigPayload(List.of(), null, Map.of(), null));
        TickerRuntimeConfigPayload updated = new TickerRuntimeConfigPayload(
                current.universe(), current.hot(), current.fundamentals(), count);
        runtimeConfigStore.save(updated);
        tickerService.reload();
        log.info("hotTickerCount override set to {}", count);
        return ResponseEntity.ok(Map.of("success", true, "effectiveCount", count));
    }
}
