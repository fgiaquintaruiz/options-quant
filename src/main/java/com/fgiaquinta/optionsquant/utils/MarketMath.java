package com.fgiaquinta.optionsquant.utils;

import org.ta4j.core.BarSeries;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.*;

public class MarketMath {

    // SOBRECARGO: Este es el que busca tu C1 actual. Crea el mapa internamente.
    public static double calculateRsRank(BarSeries stockSeries, BarSeries spySeries, int currentIndex) {
        Long2IntMap tempMap = new Long2IntOpenHashMap(spySeries.getBarCount());
        for (int i = 0; i < spySeries.getBarCount(); i++) {
            tempMap.put(spySeries.getBar(i).getEndTime().toEpochSecond(), i);
        }
        return calculateRsRank(stockSeries, spySeries, currentIndex, tempMap);
    }

    // VERSION OPTIMIZADA: La que usa el motor para maxima velocidad
    public static double calculateRsRank(BarSeries stockSeries, BarSeries spySeries, int currentIndex, Long2IntMap spyIndexMap) {
        int lookback = 200;
        if (currentIndex < lookback + 5) return 50.0;

        List<Double> rsScores = new ArrayList<>(lookback + 1);
        double currentRsScore = 0;

        for (int i = currentIndex - lookback; i <= currentIndex; i++) {
            long timeKey = stockSeries.getBar(i).getEndTime().toEpochSecond();
            if (spyIndexMap.containsKey(timeKey)) {
                int spyIdx = spyIndexMap.get(timeKey);
                if (spyIdx >= 5) {
                    double stockPc = ((stockSeries.getBar(i).getClosePrice().doubleValue() /
                            stockSeries.getBar(i - 5).getClosePrice().doubleValue()) - 1) * 100.0;
                    double spyPc = ((spySeries.getBar(spyIdx).getClosePrice().doubleValue() /
                            spySeries.getBar(spyIdx - 5).getClosePrice().doubleValue()) - 1) * 100.0;
                    double rsScore = stockPc - spyPc;
                    rsScores.add(rsScore);
                    if (i == currentIndex) currentRsScore = rsScore;
                }
            }
        }
        if (rsScores.size() < 10) return 50.0;
        Collections.sort(rsScores);
        int countBelow = 0;
        for (double score : rsScores) {
            if (score <= currentRsScore) countBelow++; else break;
        }
        return (double) countBelow / rsScores.size() * 100.0;
    }

    public static double calculateWordenStoch(BarSeries series, int index, int len, int sm) {
        if (index < len + sm) return 50.0;
        double sum = 0.0;
        for (int i = index - sm + 1; i <= index; i++) {
            double close = series.getBar(i).getClosePrice().doubleValue();
            double rank = 0.0;
            for (int j = 1; j < len; j++) {
                if (series.getBar(i - j).getClosePrice().doubleValue() < close) rank += 1.0;
            }
            sum += (100.0 / (double) (len - 1)) * rank;
        }
        return sum / (double) sm;
    }
}