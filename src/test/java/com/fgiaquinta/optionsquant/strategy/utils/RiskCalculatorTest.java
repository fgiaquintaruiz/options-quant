package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerStrategyProfile;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.DoubleNum;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskCalculatorTest {

    private StrategyData mockData;
    private BarSeries series1h;
    private ZonedDateTime now;

    @BeforeEach
    void setUp() {
        mockData = mock(StrategyData.class);
        series1h = new BaseBarSeries("Test Series");
        now = ZonedDateTime.now();
        
        // Add bars with TR=2 to avoid hitting the 1.5% cap easily
        for (int i = 0; i < 20; i++) {
            series1h.addBar(now.minusHours(20 - i), 100, 101, 99, 100, 1000);
        }
        
        when(mockData.getSeries(TimeFrame.HOUR_1)).thenReturn(series1h);
        when(mockData.getIndexForTime(series1h, now)).thenReturn(series1h.getEndIndex());
    }

    @Test
    @DisplayName("Should return entry price for TP/SL if series is missing")
    void shouldReturnEntryPriceIfSeriesMissing() {
        when(mockData.getSeries(TimeFrame.HOUR_1)).thenReturn(null);
        
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 150.0);
        
        assertThat(plan.entryPrice).isEqualTo(150.0);
        assertThat(plan.takeProfit).isEqualTo(150.0);
        assertThat(plan.stopLoss).isEqualTo(150.0);
    }

    @Test
    @DisplayName("Should use default multipliers when strategy is unknown or null")
    void shouldUseCaseDefaultMultipliers() {
        // ATR should be 2. Entry 1000. Cap = 15.
        // TP = 1000 + 2.5 * 2 = 1005
        // SL = 1000 - 2.0 * 2 = 996
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, null);
        
        assertThat(plan.takeProfit).isEqualTo(1005.0);
        assertThat(plan.stopLoss).isEqualTo(996.0);
    }

    @Test
    @DisplayName("Should cap Take Profit distance at MAX_TARGET_PCT")
    void shouldCapTargetAtMaxPct() {
        // High volatility bars (TR=50)
        for (int i = 0; i < 20; i++) {
            series1h.addBar(now.plusHours(i + 1), 100, 150, 50, 100, 1000);
        }
        ZonedDateTime future = now.plusHours(20);
        when(mockData.getIndexForTime(series1h, future)).thenReturn(series1h.getEndIndex());

        double entryPrice = 100.0;
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", future, true, entryPrice);
        
        // 1.5% cap on 100 is 1.5
        assertThat(plan.takeProfit).isEqualTo(101.5);
    }

    @Test
    @DisplayName("Should apply strategy-specific multipliers for C1 Squeeze Call")
    void shouldApplyC1SpecificMultipliers() {
        // ATR = 2. Entry 1000. Cap = 15.
        // C1 TP = 3.5 * 2 = 7. TP = 1007.
        // C1 SL = 2.5 * 2 = 5. SL = 995.
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall");
        
        assertThat(plan.takeProfit).isEqualTo(1007.0);
        assertThat(plan.stopLoss).isEqualTo(995.0);
    }

    @Test
    @DisplayName("resolveMultiplierMapKey maps spaced getName() to suffixed static map keys")
    void resolveMultiplierMapKeyMapsSpacedNames() {
        assertThat(RiskCalculator.resolveMultiplierMapKey("p5 continuation", false)).isEqualTo("p5continuationput");
        assertThat(RiskCalculator.resolveMultiplierMapKey("c1 squeeze", true)).isEqualTo("c1squeezecall");
    }

    @Test
    @DisplayName("Spaced strategy name matches same multipliers as compact map key")
    void spacedNameMatchesCompactKey() {
        TradePlan spaced = RiskCalculator.generatePlan(mockData, "AAPL", now, false, 1000.0, "p5 continuation");
        TradePlan compact = RiskCalculator.generatePlan(mockData, "AAPL", now, false, 1000.0, "p5continuationput");
        assertThat(spaced.takeProfit).isEqualTo(compact.takeProfit);
        assertThat(spaced.stopLoss).isEqualTo(compact.stopLoss);
    }

    @Test
    @DisplayName("Per-ticker profile overrides replace global map multipliers")
    void profileOverridesBeatGlobalMaps() {
        TradePlan fromMap = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall", null);

        TickerStrategyProfile profile = new TickerStrategyProfile("AAPL", "c1 squeeze call");
        profile.tpAtrMultOverride = 9.0;
        profile.slAtrMultOverride = 8.0;

        TradePlan overridden = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall", profile);
        assertThat(overridden.takeProfit).isNotEqualTo(fromMap.takeProfit);
        assertThat(overridden.stopLoss).isNotEqualTo(fromMap.stopLoss);
    }

    @Test
    @DisplayName("Null profile matches explicit null — same as global maps only")
    void nullProfileMatchesOmittedProfile() {
        TradePlan a = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall", null);
        TradePlan b = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall");
        assertThat(a.takeProfit).isEqualTo(b.takeProfit);
        assertThat(a.stopLoss).isEqualTo(b.stopLoss);
    }

    @Test
    @DisplayName("Retest SL delta widens stop for calls")
    void retestSlDeltaWidensCallStop() {
        TradePlan baseline = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, null);
        RiskCalculator.setRetestMultiplierDeltas(0, 0.5);
        try {
            TradePlan trial = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, null);
            assertThat(trial.stopLoss).isLessThan(baseline.stopLoss);
        } finally {
            RiskCalculator.clearRetestMultiplierDeltas();
        }
    }
}
