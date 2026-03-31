package com.fgiaquinta.optionsquant.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MarketRadar {
    // Thread-safe list to hold dynamically discovered tickers
    private final List<String> hotTickers = new CopyOnWriteArrayList<>();

    public void addHotTicker(String symbol) {
        if (!hotTickers.contains(symbol)) {
            hotTickers.add(symbol);
            System.out.println("🔭 Added to Radar: " + symbol);
        }
    }

    public boolean isHot(String ticker) {
        return hotTickers.contains(ticker);
    }

    public List<String> getHotTickers() {
        return hotTickers;
    }
}