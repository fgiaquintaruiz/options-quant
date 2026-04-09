package com.fgiaquinta.optionsquant.models;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OptimizationResult {

    @JsonProperty("ticker")
    public String ticker;

    @JsonProperty("strategyName")
    @JsonAlias({"strategy", "StrategyName", "name"})
    public String strategy;

    @JsonProperty("recommendedTP_ATR_Multiplier")
    @JsonAlias({"recommendedTpAtr", "takeProfitAtr", "TP_ATR", "tp"})
    public double recommendedTpAtr;

    @JsonProperty("recommendedSL_ATR_Multiplier")
    @JsonAlias({"recommendedSlAtr", "stopLossAtr", "SL_ATR", "sl"})
    public double recommendedSlAtr;

    @JsonProperty("comment")
    @JsonAlias({"insight", "reasoning"})
    public String insight;

    @JsonProperty("score")
    @JsonAlias({"confidence", "fitness", "rating"})
    public int score; // 0 to 100

    @Override
    public String toString() {
        return "Ticker: " + ticker +
                " | Strategy: " + strategy +
                " | Score: " + score +  // Add to toString
                " | TP ATR: " + recommendedTpAtr +
                " | SL ATR: " + recommendedSlAtr;
    }
}
