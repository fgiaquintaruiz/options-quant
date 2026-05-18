package com.fgiaquinta.optionsquant.strategy.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/strategy-config")
@Slf4j
public class StrategyConfigController {

    private final StrategyConfigService strategyConfigService;

    public StrategyConfigController(StrategyConfigService strategyConfigService) {
        this.strategyConfigService = strategyConfigService;
    }

    @GetMapping
    public List<StrategyConfig> findAll() {
        return strategyConfigService.findAll();
    }

    @PatchMapping("/{name}")
    public ResponseEntity<StrategyConfig> update(
            @PathVariable String name,
            @RequestBody UpdateRequest body) {
        log.debug("PATCH /api/strategy-config/{} enabledLive={} enabledBacktest={}", name, body.enabledLive(), body.enabledBacktest());
        return strategyConfigService.partialUpdate(name, body.enabledLive(), body.enabledBacktest())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    record UpdateRequest(Boolean enabledLive, Boolean enabledBacktest) {}
}
