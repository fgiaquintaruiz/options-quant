package com.fgiaquinta.optionsquant.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisResult {
    public String bias;        // "BULLISH", "BEARISH", "NEUTRAL"
    public String industry;    // e.g., "Aerospace & Defense", "Semiconductors"
    public List<String> tickers; // e.g., ["LMT", "RTX"]

    @Override
    public String toString() {
        return "Bias: " + bias + " | Industry: " + industry + " | Tickers: " + tickers;
    }
}