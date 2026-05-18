package com.fgiaquinta.optionsquant.strategy.config;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class StrategyConfig {
    String strategyName;
    boolean enabledBacktest;
    boolean enabledLive;
    String notes;
    long updatedAt;
    String updatedBy;
}
