package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradingServiceTest {

    private StrategyScannerService scannerService;
    private OrderExecutionService orderExecutionService;
    private AccountManager accountManager;
    private IbkrProperties ibkrProperties;
    private StaircaseReEntryFilter staircaseFilter;
    private TickerMemory tickerMemory;
    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        scannerService = mock(StrategyScannerService.class);
        orderExecutionService = mock(OrderExecutionService.class);
        accountManager = mock(AccountManager.class);
        ibkrProperties = mock(IbkrProperties.class);
        staircaseFilter = mock(StaircaseReEntryFilter.class);
        tickerMemory = mock(TickerMemory.class);

        tradingService = new TradingService(
                scannerService,
                orderExecutionService,
                accountManager,
                ibkrProperties,
                staircaseFilter,
                tickerMemory
        );
    }

    @Test
    @DisplayName("scanAndExecute should skip trades if OrderExecutionService returns null (disconnected)")
    void shouldSkipTradesIfExecutionFails() {
        // Arrange
        TradePlan plan = new TradePlan(100.0, 110.0, 90.0, true, null);
        StrategyScannerService.Signal signal = new StrategyScannerService.Signal(
                "AAPL", "squeeze", "CALL", 100.0, ZonedDateTime.now(), plan, "hammer"
        );
        StrategyScannerService.ScanResult scanResult = new StrategyScannerService.ScanResult(
                1, 1, List.of(signal), 100
        );

        when(scannerService.scanAll(anyBoolean(), anyBoolean())).thenReturn(scanResult);
        when(accountManager.calculateQuantity(anyDouble(), anyDouble())).thenReturn(1);
        when(accountManager.canOpenNewTrade(anyInt())).thenReturn(true);
        when(ibkrProperties.autoExecute()).thenReturn(true);
        when(staircaseFilter.isReEntryAllowed(anyString(), anyBoolean(), anyDouble())).thenReturn(true);
        when(tickerMemory.getPositionSizeMultiplier(anyString())).thenReturn(1.0);
        
        // Simulate execution failure (e.g. disconnected)
        when(orderExecutionService.placeOptionBracket(anyString(), anyBoolean(), anyInt(), any(), anyString()))
                .thenReturn(null);

        // Act
        TradingService.TradingResult result = tradingService.scanAndExecute(true, 0, 10);

        // Assert
        assertThat(result.executions()).hasSize(1);
        assertThat(result.executions().get(0).success()).isFalse();
        assertThat(result.executions().get(0).message()).contains("placement failed");
        verify(accountManager, never()).addActiveTrade();
    }

    @Test
    @DisplayName("executeManualTrade should return null if OrderExecutionService is disconnected")
    void executeManualTradeShouldReturnNullOnFailure() {
        // Arrange
        when(orderExecutionService.placeOptionBracket(anyString(), anyBoolean(), anyInt(), any(), anyString()))
                .thenReturn(null);

        // Act
        OrderExecutionService.OrderResult result = tradingService.executeManualTrade("AAPL", "manual", "CALL", 150.0);

        // Assert
        assertThat(result).isNull();
    }
}
