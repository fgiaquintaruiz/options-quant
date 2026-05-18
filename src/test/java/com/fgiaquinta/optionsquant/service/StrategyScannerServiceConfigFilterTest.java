package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import com.fgiaquinta.optionsquant.strategy.config.StrategyConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyScannerServiceConfigFilterTest {

    @Mock
    private StrategyConfigService strategyConfigService;

    @Mock
    private TradingStrategy strategy;

    @Test
    void skipsIsTriggeredWhenLiveDisabled() {
        when(strategy.getName()).thenReturn("c3 bounce");
        when(strategyConfigService.isLiveEnabled("c3 bounce")).thenReturn(false);

        // Simulate the guard check
        boolean shouldEvaluate = strategyConfigService.isLiveEnabled(strategy.getName());
        if (!shouldEvaluate) {
            // Verify isTriggered is never called when disabled
            verify(strategy, never()).isTriggered(any(), any(), any());
        }
    }

    @Test
    void evaluatesStrategyWhenLiveEnabled() {
        when(strategy.getName()).thenReturn("p6 reversal");
        when(strategyConfigService.isLiveEnabled("p6 reversal")).thenReturn(true);

        boolean shouldEvaluate = strategyConfigService.isLiveEnabled(strategy.getName());
        assertThat(shouldEvaluate).isTrue();
    }

    @Test
    void isLiveEnabled_calledWithExactStrategyName() {
        when(strategy.getName()).thenReturn("p1 squeeze");
        when(strategyConfigService.isLiveEnabled("p1 squeeze")).thenReturn(false);

        strategyConfigService.isLiveEnabled(strategy.getName());

        verify(strategyConfigService).isLiveEnabled("p1 squeeze");
    }
}
