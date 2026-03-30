package com.fgiaquinta.optionsquant.models;

public record MarketRequest(String ticker, TimeFrame timeFrame) {
    public String getCacheKey() {
        return ticker + "_" + timeFrame.getFileSuffix();
    }
}