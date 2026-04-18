package com.fgiaquinta.optionsquant.strategy.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickPatternDetectorTest {

    private BarSeries series;
    private ZonedDateTime now;

    @BeforeEach
    void setUp() {
        series = new BaseBarSeries("Test Series");
        now = ZonedDateTime.now();
    }

    @Test
    @DisplayName("Should detect Bullish Marubozu")
    void shouldDetectBullishMarubozu() {
        // Open 100, Close 110, High 110, Low 100 (No shadows)
        series.addBar(now, 100, 110, 100, 110, 1000);
        
        String pattern = CandlestickPatternDetector.detectPattern(series, 0);
        
        assertThat(pattern).isEqualTo("bullish_marubozu");
        assertThat(CandlestickPatternDetector.isBullishPattern(pattern)).isTrue();
    }

    @Test
    @DisplayName("Should detect Hammer pattern")
    void shouldDetectHammer() {
        // Hammer: Small body at top, long lower shadow
        // Open 108, Close 110, High 110.5, Low 100
        // Body = 2, Lower Shadow = 8, Upper Shadow = 0.5, Range = 10.5
        series.addBar(now, 108, 110.5, 100, 110, 1000);
        
        String pattern = CandlestickPatternDetector.detectPattern(series, 0);
        
        assertThat(pattern).isEqualTo("hammer");
        assertThat(CandlestickPatternDetector.isBullishPattern(pattern)).isTrue();
    }

    @Test
    @DisplayName("Should detect Bullish Engulfing")
    void shouldDetectBullishEngulfing() {
        // Bar 1: Bearish, small (Open 105, Close 102)
        series.addBar(now.minusMinutes(5), 105, 105, 102, 102, 1000);
        
        // Bar 2: Bullish, engulfs Bar 1 (Open 101, Close 106)
        series.addBar(now, 101, 107, 100, 106, 1000);
        
        String pattern = CandlestickPatternDetector.detectPattern(series, 1);
        
        assertThat(pattern).isEqualTo("bullish_engulfing");
    }

    @Test
    @DisplayName("Should detect Doji")
    void shouldDetectDoji() {
        // Open 100, Close 100.1, High 105, Low 95
        // Body = 0.1, Range = 10 -> Body Ratio = 0.01 (< 0.1)
        series.addBar(now, 100, 105, 95, 100.1, 1000);
        
        String pattern = CandlestickPatternDetector.detectPattern(series, 0);
        
        assertThat(pattern).contains("doji");
        assertThat(CandlestickPatternDetector.isIndecisionPattern(pattern)).isTrue();
    }
}
