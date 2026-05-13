package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.FillResult;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — RED/GREEN for SimulatedFillEngine slippage rate.
 *
 * <p>Business context: slippage is modelled as a percentage of the underlying stock price.
 * With 0.5% slippage (old) the round-trip cost on a $100 underlying is $1.00, which erases
 * a TP of 0.67% ($0.67) → netPnl goes negative.
 * With 0.08% slippage (new) the round-trip cost is $0.16, leaving netPnl = +$0.51 on the
 * same TP. This aligns with realistic market-order slippage for liquid large-cap options
 * (≈ 2% of the option contract price at delta 0.60, contract ≈ 4% of underlying).
 */
class SimulatedFillEngineTest {

    // Fixtures
    private static final double ENTRY_PRICE    = 100.0;   // underlying price
    private static final double TP_PRICE       = 100.67;  // +0.67% — typical TP target
    private static final double ZERO_COMMISSION = 0.0;    // isolate slippage effect

    // ── RED scenario: old slippage (0.5%) → netPnl must be NEGATIVE at TP ────────

    @Test
    void fillExit_withOldSlippage_atTP_hasNegativeNetPnl() {
        double oldSlippage = 0.005; // 0.5% — the value we are REPLACING

        SimulatedFillEngine engine = new SimulatedFillEngine(oldSlippage, ZERO_COMMISSION);

        FillResult entry = engine.fillEntry("SPY", "CALL", 1, tradePlan(ENTRY_PRICE), now());
        FillResult exit  = engine.fillExit("SPY", "CALL", 1, TP_PRICE, now());

        // grossPnl = TP_PRICE − entry.fillPrice
        double grossPnl = TP_PRICE - entry.fillPrice();
        // slippage cost = entry slippage + exit slippage (both adversarial)
        double totalSlippage = entry.slippage() + exit.slippage();
        double netPnl = grossPnl - totalSlippage;

        // With 0.5%: entry fill = 100.50, exit fill = 100.67 − 0.5335 = 100.1365
        // grossPnl = 100.67 − 100.50 = 0.17, totalSlippage = 0.50 + 0.5335 = 1.0335
        // netPnl ≈ 0.17 − 1.03 = −0.86 → NEGATIVE (confirms RED)
        assertThat(netPnl)
                .as("old 0.5%% slippage should make netPnl negative at a 0.67%% TP")
                .isLessThan(0.0);
    }

    // ── GREEN scenario: new slippage (0.08%) → netPnl must be POSITIVE at TP ─────

    @Test
    void fillExit_withNewSlippage_atTP_hasPositiveNetPnl() {
        double newSlippage = 0.0008; // 0.08% — the value we are INTRODUCING

        SimulatedFillEngine engine = new SimulatedFillEngine(newSlippage, ZERO_COMMISSION);

        FillResult entry = engine.fillEntry("SPY", "CALL", 1, tradePlan(ENTRY_PRICE), now());
        FillResult exit  = engine.fillExit("SPY", "CALL", 1, TP_PRICE, now());

        double grossPnl      = TP_PRICE - entry.fillPrice();
        double totalSlippage = entry.slippage() + exit.slippage();
        double netPnl        = grossPnl - totalSlippage;

        // With 0.08%: entry fill = 100.08, exit fill = 100.67 − 0.08054 = 100.5895
        // grossPnl = 100.67 − 100.08 = 0.59, totalSlippage = 0.08 + 0.0806 = 0.1606
        // netPnl ≈ 0.59 − 0.16 = +0.43 → POSITIVE (confirms GREEN)
        assertThat(netPnl)
                .as("new 0.08%% slippage should leave netPnl positive at a 0.67%% TP")
                .isGreaterThan(0.0);
    }

    // ── Direction invariant: slippage always hurts the trader (adversarial) ────────

    @Test
    void fillEntry_slippageIsAlwaysAdversarial_callBuysHigher() {
        SimulatedFillEngine engine = new SimulatedFillEngine(0.0008, ZERO_COMMISSION);

        FillResult result = engine.fillEntry("SPY", "CALL", 1, tradePlan(ENTRY_PRICE), now());

        assertThat(result.fillPrice())
                .as("CALL entry fill price must be ABOVE the base price (slippage is adversarial)")
                .isGreaterThan(ENTRY_PRICE);
    }

    @Test
    void fillExit_call_slippageIsAlwaysAdversarial_exitsLower() {
        SimulatedFillEngine engine = new SimulatedFillEngine(0.0008, ZERO_COMMISSION);

        FillResult result = engine.fillExit("SPY", "CALL", 1, TP_PRICE, now());

        assertThat(result.fillPrice())
                .as("CALL exit fill price must be BELOW the exit price (slippage is adversarial)")
                .isLessThan(TP_PRICE);
    }

    @Test
    void fillExit_put_slippageIsAlwaysAdversarial_exitsHigher() {
        SimulatedFillEngine engine = new SimulatedFillEngine(0.0008, ZERO_COMMISSION);

        // For PUT, exit at SL (price went up, so we exit above expected)
        double slPrice = 99.67;
        FillResult result = engine.fillExit("SPY", "PUT", 1, slPrice, now());

        assertThat(result.fillPrice())
                .as("PUT exit fill price must be ABOVE the exit price (slippage is adversarial)")
                .isGreaterThan(slPrice);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static TradePlan tradePlan(double entryPrice) {
        return new TradePlan(entryPrice, entryPrice * 1.0067, entryPrice * 0.9967, true, LocalTime.of(15, 55));
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now();
    }
}
