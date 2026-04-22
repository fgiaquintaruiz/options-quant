package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.dto.TickerFundamentalPayload;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import com.fgiaquinta.optionsquant.service.TickerRuntimeConfigStore;
import com.fgiaquinta.optionsquant.service.TickerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ABM for ticker universe, HOT priority, and fundamental overrides (persisted to {@code data/ticker-runtime.json}).
 */
@RestController
@RequestMapping("/api/ticker-config")
@RequiredArgsConstructor
public class TickerConfigController {

    private final TickerService tickerService;
    private final TickerRuntimeConfigStore runtimeConfigStore;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        tickerService.loadTickers();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("universe", tickerService.getTickerSymbols());
        body.put("hot", tickerService.getHotTickers());
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<String, TickerInfo> e : tickerService.snapshotMapForConfigApi().entrySet()) {
            entries.put(e.getKey(), TickerFundamentalPayload.fromTickerInfo(e.getValue()));
        }
        body.put("entries", entries);
        body.put("fundamentals", entries);
        var rt = runtimeConfigStore.load();
        body.put("hasRuntimeFile", rt.isPresent());
        rt.ifPresent(c -> {
            body.put("runtimeUniverseEmpty", c.universe().isEmpty());
            body.put("runtimeHotEmpty", c.hot().isEmpty());
        });
        return ResponseEntity.ok(body);
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
        var current = runtimeConfigStore.load().orElse(new TickerRuntimeConfigPayload(List.of(), List.of(), Map.of()));
        List<String> universe = current.universe().stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.equals(sym))
                .toList();
        List<String> hot = current.hot().stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.equals(sym))
                .toList();
        Map<String, TickerFundamentalPayload> fund = new LinkedHashMap<>(current.fundamentals());
        fund.remove(sym);
        TickerRuntimeConfigPayload next = new TickerRuntimeConfigPayload(universe, hot, fund);
        runtimeConfigStore.save(next);
        tickerService.reload();
        return ResponseEntity.ok(Map.of("success", true, "deleted", sym));
    }
}
