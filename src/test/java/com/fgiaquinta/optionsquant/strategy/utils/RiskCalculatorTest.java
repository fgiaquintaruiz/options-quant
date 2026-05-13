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

import java.time.ZonedDateTime;

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
    @DisplayName("Fixed-pct targets are independent of strategy name")
    void fixedPctTargets_areIndependentOfStrategyName() {
        TradePlan withStrategy = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall");
        TradePlan withNull     = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, (String) null);

        assertThat(withStrategy.takeProfit).isEqualTo(withNull.takeProfit);
        assertThat(withStrategy.stopLoss).isEqualTo(withNull.stopLoss);
    }

    @Test
    @DisplayName("Fixed-pct targets are independent of TickerStrategyProfile")
    void fixedPctTargets_areIndependentOfProfile() {
        TradePlan noProfile = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall", null);

        TickerStrategyProfile profile = new TickerStrategyProfile("AAPL", "c1 squeeze call");
        profile.tpAtrMultOverride = 9.0;
        profile.slAtrMultOverride = 8.0;

        TradePlan withProfile = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall", profile);
        assertThat(withProfile.takeProfit).isEqualTo(noProfile.takeProfit);
        assertThat(withProfile.stopLoss).isEqualTo(noProfile.stopLoss);
    }

    @Test
    @DisplayName("Null profile matches explicit null — fixed-pct produces same targets")
    void nullProfileMatchesOmittedProfile() {
        TradePlan a = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall", null);
        TradePlan b = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, "c1squeezecall");
        assertThat(a.takeProfit).isEqualTo(b.takeProfit);
        assertThat(a.stopLoss).isEqualTo(b.stopLoss);
    }

    @Test
    @DisplayName("setRetestMultiplierDeltas is a no-op — targets remain fixed")
    void retestMultiplierDeltas_areNoOp() {
        TradePlan baseline = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, null);
        RiskCalculator.setRetestMultiplierDeltas(0, 0.5);
        try {
            TradePlan trial = RiskCalculator.generatePlan(mockData, "AAPL", now, true, 1000.0, null);
            assertThat(trial.stopLoss).isEqualTo(baseline.stopLoss);
            assertThat(trial.takeProfit).isEqualTo(baseline.takeProfit);
        } finally {
            RiskCalculator.clearRetestMultiplierDeltas();
        }
    }

    // ── Fixed-percentage TP/SL tests (new business rule) ──────────────────────

    @Test
    @DisplayName("calculateTargets_forCall_tpIs067pctAboveEntry")
    void calculateTargets_forCall_tpIs067pctAboveEntry() {
        double entryPrice = 100.0;
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, entryPrice);

        double expectedTp = Math.round(entryPrice * (1 + 0.0067) * 100.0) / 100.0;
        assertThat(plan.takeProfit).isEqualTo(expectedTp);
    }

    @Test
    @DisplayName("calculateTargets_forPut_tpIs067pctBelowEntry")
    void calculateTargets_forPut_tpIs067pctBelowEntry() {
        double entryPrice = 100.0;
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, false, entryPrice);

        double expectedTp = Math.round(entryPrice * (1 - 0.0067) * 100.0) / 100.0;
        assertThat(plan.takeProfit).isEqualTo(expectedTp);
    }

    @Test
    @DisplayName("calculateTargets_forCall_slIs033pctBelowEntry")
    void calculateTargets_forCall_slIs033pctBelowEntry() {
        double entryPrice = 100.0;
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, entryPrice);

        double expectedSl = Math.round(entryPrice * (1 - 0.0033) * 100.0) / 100.0;
        assertThat(plan.stopLoss).isEqualTo(expectedSl);
    }

    @Test
    @DisplayName("calculateTargets_forPut_slIs033pctAboveEntry")
    void calculateTargets_forPut_slIs033pctAboveEntry() {
        double entryPrice = 100.0;
        TradePlan plan = RiskCalculator.generatePlan(mockData, "AAPL", now, false, entryPrice);

        double expectedSl = Math.round(entryPrice * (1 + 0.0033) * 100.0) / 100.0;
        assertThat(plan.stopLoss).isEqualTo(expectedSl);
    }

    @Test
    @DisplayName("calculateTargets_callAndPut_sameTpPctDistanceFromEntry")
    void calculateTargets_callAndPut_sameTpPctDistanceFromEntry() {
        double entryPrice = 100.0;
        TradePlan callPlan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, entryPrice);
        TradePlan putPlan  = RiskCalculator.generatePlan(mockData, "AAPL", now, false, entryPrice);

        double callTpPct = (callPlan.takeProfit - entryPrice) / entryPrice;
        double putTpPct  = (entryPrice - putPlan.takeProfit) / entryPrice;
        assertThat(callTpPct).isEqualTo(putTpPct, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("calculateTargets_callAndPut_sameSlPctDistanceFromEntry")
    void calculateTargets_callAndPut_sameSlPctDistanceFromEntry() {
        double entryPrice = 100.0;
        TradePlan callPlan = RiskCalculator.generatePlan(mockData, "AAPL", now, true, entryPrice);
        TradePlan putPlan  = RiskCalculator.generatePlan(mockData, "AAPL", now, false, entryPrice);

        double callSlPct = (entryPrice - callPlan.stopLoss) / entryPrice;
        double putSlPct  = (putPlan.stopLoss - entryPrice) / entryPrice;
        assertThat(callSlPct).isEqualTo(putSlPct, org.assertj.core.api.Assertions.within(1e-9));
    }
}
