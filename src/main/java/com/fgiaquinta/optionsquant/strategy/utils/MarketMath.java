package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.Candle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Market math utilities for relative strength and stochastic calculations.
 * Migrated from legacy MarketMath.java.
 */
public class MarketMath {

    private MarketMath() {}

    /**
     * Calculates the Relative Strength Rank of a stock vs SPY over the last 200 periods.
     * RS Rank = percentile rank of the stock's 5-period return vs SPY's 5-period return.
     *
     * @param stockCandles Stock OHLCV candles
     * @param spyCandles SPY OHLCV candles (should cover at least the same time range)
     * @param lookback Number of periods to rank over (default 200)
     * @return RS Rank from 0 (weakest) to 100 (strongest), or 50.0 if insufficient data
     */
    public static double calculateRsRank(List<Candle> stockCandles, List<Candle> spyCandles, int lookback) {
        if (stockCandles.size() < lookback + 5 || spyCandles.size() < 5) {
            return 50.0;
        }

        List<Double> rsScores = new ArrayList<>(lookback + 1);
        double currentRsScore = 0;

        for (int i = stockCandles.size() - lookback; i < stockCandles.size(); i++) {
            // Find matching SPY candle by timestamp (approximate match by epoch second)
            long timeKey = stockCandles.get(i).timestamp().toEpochSecond();
            int spyIdx = findCandleIndexByTime(spyCandles, timeKey);

            if (spyIdx >= 5) {
                double stockPc = ((stockCandles.get(i).close() /
                        stockCandles.get(i - 5).close()) - 1) * 100.0;
                double spyPc = ((spyCandles.get(spyIdx).close() /
                        spyCandles.get(spyIdx - 5).close()) - 1) * 100.0;
                double rsScore = stockPc - spyPc;
                rsScores.add(rsScore);
                if (i == stockCandles.size() - 1) {
                    currentRsScore = rsScore;
                }
            }
        }

        if (rsScores.size() < 10) return 50.0;

        Collections.sort(rsScores);
        int countBelow = 0;
        for (double score : rsScores) {
            if (score <= currentRsScore) countBelow++;
            else break;
        }

        return (double) countBelow / rsScores.size() * 100.0;
    }

    /**
     * Simplified RS Rank using two candle lists that are assumed to be aligned by index.
     * Use this when stockCandles and spyCandles have the same timestamps at each index.
     */
    public static double calculateRsRankAligned(List<Candle> stockCandles, List<Candle> spyCandles, int lookback) {
        int n = Math.min(stockCandles.size(), spyCandles.size());
        if (n < lookback + 5) return 50.0;

        List<Double> rsScores = new ArrayList<>(lookback + 1);
        double currentRsScore = 0;

        for (int i = n - lookback; i < n; i++) {
            if (i >= 5 && (i - 5) < spyCandles.size()) {
                double stockPc = ((stockCandles.get(i).close() /
                        stockCandles.get(i - 5).close()) - 1) * 100.0;
                double spyPc = ((spyCandles.get(i).close() /
                        spyCandles.get(i - 5).close()) - 1) * 100.0;
                double rsScore = stockPc - spyPc;
                rsScores.add(rsScore);
                if (i == n - 1) currentRsScore = rsScore;
            }
        }

        if (rsScores.size() < 10) return 50.0;

        Collections.sort(rsScores);
        int countBelow = 0;
        for (double score : rsScores) {
            if (score <= currentRsScore) countBelow++;
            else break;
        }

        return (double) countBelow / rsScores.size() * 100.0;
    }

    /**
     * Calculates the Worden Stochastic value.
     * Worden Stochastic ranks the current close price relative to the last 'len' bars,
     * then smooths the result over 'sm' periods.
     *
     * @param candles OHLCV candles
     * @param index Current bar index
     * @param len Lookback period for ranking (default 14)
     * @param sm Smoothing period (default 3)
     * @return Worden Stochastic value (0-100), or 50.0 if insufficient data
     */
    public static double calculateWordenStoch(List<Candle> candles, int index, int len, int sm) {
        if (index < len + sm || index >= candles.size()) return 50.0;

        double sum = 0.0;
        for (int i = index - sm + 1; i <= index; i++) {
            double close = candles.get(i).close();
            double rank = 0.0;
            for (int j = 1; j < len; j++) {
                if (candles.get(i - j).close() < close) rank += 1.0;
            }
            sum += (100.0 / (double) (len - 1)) * rank;
        }
        return sum / (double) sm;
    }

    /**
     * Finds the candle index closest to the given epoch second.
     * Returns -1 if no match found within tolerance.
     */
    private static int findCandleIndexByTime(List<Candle> candles, long targetEpochSec) {
        // Binary search would be faster for large lists, but linear is fine for ~1000 candles
        for (int i = 0; i < candles.size(); i++) {
            if (Math.abs(candles.get(i).timestamp().toEpochSecond() - targetEpochSec) < 300) {
                return i;
            }
        }
        return -1;
    }
}
