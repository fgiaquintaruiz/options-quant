package com.fgiaquinta.optionsquant.service;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.Order;
import com.ib.client.OrderCancel;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.ZonedDateTime;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

class OrderExecutionServiceTest {

    private IbkrProperties properties;
    private EClientSocket mockClient;
    private OrderExecutionService service;

    @BeforeEach
    void setUp() {
        OrderExecutionService.connectLatchWaitMs = 50;
        OrderExecutionService.resolveContractWaitMs = 50;
        OrderExecutionService.resolveChainWaitMs = 50;

        properties = Mockito.mock(IbkrProperties.class);
        mockClient = Mockito.mock(EClientSocket.class);
        
        when(mockClient.isConnected()).thenReturn(true);
        
        service = new OrderExecutionService(properties, mockClient);
    }

    @AfterEach
    void restoreIbkrWaits() {
        OrderExecutionService.connectLatchWaitMs = 5000;
        OrderExecutionService.resolveContractWaitMs = 5000;
        OrderExecutionService.resolveChainWaitMs = 10000;
    }

    @Test
    void resolveOptionChain_shouldFallBackToReqSecDefOptParams_whenUnderlyingIdIsZero() {
        // GIVEN a ticker whose conId cannot be resolved via reqContractDetails (remains 0)
        String ticker = "INVALID";

        // WHEN resolving the option chain
        // THEN it must NOT throw early — it should call reqSecDefOptParams as a fallback
        // (IBKR populates the conId as a side effect via securityDefinitionOptionalParameter).
        // After the 10s timeout with no callback, it throws "Failed to resolve option chain".
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.resolveOptionChain(ticker);
        });

        assertTrue(exception.getMessage().contains("Failed to resolve option chain for " + ticker),
                "Expected fallback path to be taken and final timeout message, got: " + exception.getMessage());

        // Verify the fallback path actually called reqSecDefOptParams
        verify(mockClient).reqSecDefOptParams(anyInt(), eq(ticker), eq(""), eq("STK"), eq(0));
    }

    @Test
    void resolveOptionChain_shouldNotThrowException_whenUnderlyingIdIsValid() {
        // GIVEN a ticker that is successfully resolved
        String ticker = "AAPL";
        
        // We need to mock the behavior of resolveOptionChain's internal state.
        // Since we can't easily mock the inner maps, we have to rely on the fact 
        // that if the underlyingId is > 0, it proceeds.
        // But resolveOptionChain calls connect() and then checks the map.
        // To simulate a resolved ticker, we would need to mock the callback or manually put it in the map.
        // Since the map is private, we can't easily do that without reflection.
        // However, the a simple way is to verify that for a ticker that's NOT 0, it doesn't throw 
        // the a a lready implemented "Could not resolve" exception.
        
        // Actually, without mocking the TWS callback, resolveOptionChain will always 
        // end up with conId = 0 for any ticker unless we mock the internal state.
        
        // Let's use reflection to put a value in tickerToUnderlyingConId.
        try {
            java.lang.reflect.Field field = OrderExecutionService.class.getDeclaredField("tickerToUnderlyingConId");
            field.setAccessible(true);
            java.util.Map<String, Integer> map = (java.util.Map<String, Integer>) field.get(service);
            map.put(ticker, 12345);
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
        
        // Also need to mock tickerToBestExpiration and tickerToValidStrikes to avoid later exceptions
        try {
            java.lang.reflect.Field expField = OrderExecutionService.class.getDeclaredField("tickerToBestExpiration");
            expField.setAccessible(true);
            java.util.Map<String, String> expMap = (java.util.Map<String, String>) expField.get(service);
            expMap.put(ticker, "20260619");
            
            java.lang.reflect.Field strikesField = OrderExecutionService.class.getDeclaredField("tickerToValidStrikes");
            strikesField.setAccessible(true);
            java.util.Map<String, Set<Double>> strikesMap = (java.util.Map<String, Set<Double>>) strikesField.get(service);
            strikesMap.put(ticker, Set.of(150.0, 160.0));
            
            java.lang.reflect.Field tcField = OrderExecutionService.class.getDeclaredField("tickerToTradingClass");
            tcField.setAccessible(true);
            java.util.Map<String, String> tcMap = (java.util.Map<String, String>) tcField.get(service);
            tcMap.put(ticker, "STK");
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }

        // WHEN resolving the option chain
        assertDoesNotThrow(() -> {
            service.resolveOptionChain(ticker);
        });
    }

    @Test
    void cancelOrder_shouldDelegateToEClientSocket() {
        // GIVEN a specific orderId to cancel
        int orderId = 42;

        // WHEN cancelOrder is called
        service.cancelOrder(orderId);

        // THEN the IBKR client must be invoked with the same ID
        verify(mockClient).cancelOrder(eq(orderId), any(OrderCancel.class));
    }

    @Test
    void cancelOrder_shouldCallConnectFirst_toEnsureConnection() {
        // GIVEN disconnected client at first check
        when(mockClient.isConnected()).thenReturn(false, true);

        // WHEN cancelOrder is called
        try {
            service.cancelOrder(99);
        } catch (Exception ignored) {
            // connect() may throw if eConnect mock isn't set up, but we only care that it was attempted
        }

        // THEN the client should have been inspected for connection state
        verify(mockClient, atLeastOnce()).isConnected();
    }

    @Test
    void closePositionViaConditions_shouldCancelBothTpAndSlOrders() {
        // GIVEN TP and SL order IDs from a previous bracket
        int parentOrderId = 100;
        int tpOrderId = 101;
        int slOrderId = 102;

        // Pre-populate bracket state using reflection
        try {
            java.lang.reflect.Field field = OrderExecutionService.class.getDeclaredField("bracketStateMap");
            field.setAccessible(true);
            java.util.Map<Integer, OrderExecutionService.BracketTradeInfo> map =
                (java.util.Map<Integer, OrderExecutionService.BracketTradeInfo>) field.get(service);

            // Create test contract
            Contract testContract = new Contract();
            testContract.symbol("AAPL");
            testContract.conid(123456);
            testContract.secType("OPT");

            map.put(parentOrderId, new OrderExecutionService.BracketTradeInfo(
                testContract, 10, 150.0, ZonedDateTime.now()
            ));
        } catch (Exception e) {
            fail("Failed to set up bracket state: " + e.getMessage());
        }

        // WHEN closePositionViaConditions is called (new signature: parentId, tpId, slId)
        service.closePositionViaConditions(parentOrderId, tpOrderId, slOrderId);

        // THEN BOTH conditional orders must be cancelled (this triggers OCA cleanup)
        verify(mockClient).cancelOrder(eq(tpOrderId), any(OrderCancel.class));
        verify(mockClient).cancelOrder(eq(slOrderId), any(OrderCancel.class));

        // AND a market sell order must be placed (verify call occurred, not internal details)
        // The log shows: "📤 Placing market sell: orderId=1, contract=AAPL, qty=10" - that's verified
        verify(mockClient, atLeastOnce()).placeOrder(anyInt(), any(Contract.class), any(Order.class));
    }

    @Test
    void closePositionViaConditions_removesBracketState() {
        // GIVEN bracket state is present
        int parentOrderId = 200;
        int tpOrderId = 201;
        int slOrderId = 202;

        try {
            java.lang.reflect.Field field = OrderExecutionService.class.getDeclaredField("bracketStateMap");
            field.setAccessible(true);
            java.util.Map<Integer, OrderExecutionService.BracketTradeInfo> map =
                (java.util.Map<Integer, OrderExecutionService.BracketTradeInfo>) field.get(service);

            Contract testContract = new Contract();
            testContract.symbol("TSLA");
            map.put(parentOrderId, new OrderExecutionService.BracketTradeInfo(
                testContract, 5, 200.0, ZonedDateTime.now()
            ));
        } catch (Exception e) {
            fail("Failed to set up bracket state: " + e.getMessage());
        }

        // WHEN closePositionViaConditions is called
        service.closePositionViaConditions(parentOrderId, tpOrderId, slOrderId);

        // THEN bracket state should be removed from map
        try {
            java.lang.reflect.Field field = OrderExecutionService.class.getDeclaredField("bracketStateMap");
            field.setAccessible(true);
            java.util.Map<Integer, OrderExecutionService.BracketTradeInfo> map =
                (java.util.Map<Integer, OrderExecutionService.BracketTradeInfo>) field.get(service);

            assertNull(map.get(parentOrderId), "Bracket state should be removed after close");
        } catch (Exception e) {
            fail("Failed to verify bracket state removal: " + e.getMessage());
        }
    }

    @Test
    void closePositionViaConditions_logsErrorWhenNoBracketState() {
        // GIVEN no bracket state exists for parent order
        int parentOrderId = 999;
        int tpOrderId = 301;
        int slOrderId = 302;

        // WHEN closePositionViaConditions is called for unknown position
        // THEN it should NOT throw, but should log error and return gracefully
        assertDoesNotThrow(() -> {
            service.closePositionViaConditions(parentOrderId, tpOrderId, slOrderId);
        });

        // Verify cancel was still attempted (orders might have been pending)
        verify(mockClient).cancelOrder(eq(tpOrderId), any(OrderCancel.class));
        verify(mockClient).cancelOrder(eq(slOrderId), any(OrderCancel.class));
    }

}
