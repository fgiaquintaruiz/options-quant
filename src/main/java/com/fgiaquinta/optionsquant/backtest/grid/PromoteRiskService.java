package com.fgiaquinta.optionsquant.backtest.grid;

import com.fgiaquinta.optionsquant.service.TickerMemory;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Writes per-ticker+strategy ATR multipliers to {@link TickerMemory} (optional dry run).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromoteRiskService {

    private final TickerMemory tickerMemory;

    public PromoteResult promote(PromoteRiskRequest request) {
        if (request.ticker() == null || request.ticker().isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (request.strategyName() == null || request.strategyName().isBlank()) {
            throw new IllegalArgumentException("strategyName is required");
        }

        String mapKey = RiskCalculator.resolveMultiplierMapKey(request.strategyName(), request.isCall());
        double baseTp = RiskCalculator.baseTpMultiplier(mapKey);
        double baseSl = RiskCalculator.baseSlMultiplier(mapKey);
        double tp = baseTp + request.tpMultiplierDelta();
        double sl = baseSl + request.slMultiplierDelta();

        if (request.dryRun()) {
            log.info("[PROMOTE] dryRun=true ticker={} strategy={} mapKey={} would set tpAtrMult={} slAtrMult={} (base {}/{})",
                    request.ticker(), request.strategyName(), mapKey, tp, sl, baseTp, baseSl);
            return new PromoteResult(false, "dry run — ticker memory unchanged", sl, tp);
        }

        tickerMemory.applyAtrMultiplierOverrides(request.ticker(), request.strategyName(), sl, tp);
        log.info("[PROMOTE] audit persisted ticker={} strategy={} mapKey={} tpAtrMult={} slAtrMult={} tpΔ={} slΔ={}",
                request.ticker(), request.strategyName(), mapKey, tp, sl, request.tpMultiplierDelta(), request.slMultiplierDelta());
        return new PromoteResult(true, "saved to ticker memory", sl, tp);
    }
}
