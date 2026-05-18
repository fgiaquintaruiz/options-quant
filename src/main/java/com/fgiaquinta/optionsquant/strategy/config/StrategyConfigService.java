package com.fgiaquinta.optionsquant.strategy.config;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class StrategyConfigService {

    private final StrategyConfigRepository repository;

    public StrategyConfigService(StrategyConfigRepository repository) {
        this.repository = repository;
    }

    public List<String> getActiveLive() {
        return repository.findActiveLive();
    }

    public List<String> getActiveBacktest() {
        return repository.findActiveBacktest();
    }

    public boolean isLiveEnabled(String strategyName) {
        return getActiveLive().contains(strategyName);
    }

    public Optional<StrategyConfig> findByName(String name) {
        return repository.findByName(name);
    }

    public List<StrategyConfig> findAll() {
        return repository.findAll();
    }

    public int update(String name, boolean enabledBacktest, boolean enabledLive, String notes, String updatedBy) {
        return repository.update(name, enabledBacktest, enabledLive, notes, updatedBy);
    }
}
