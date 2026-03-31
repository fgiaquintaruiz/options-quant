package com.fgiaquinta.optionsquant.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OptimizationResult {
    public String ticker;
    public String strategy;
    public String insight;
    public double recommendedTpAtr;
    public double recommendedSlAtr;

    @Override
    public String toString() {
        return "Ticker: " + ticker +
                " | Strategy: " + strategy +
                " | TP ATR: " + recommendedTpAtr +
                " | SL ATR: " + recommendedSlAtr +
                " | Insight: " + insight;
    }
}