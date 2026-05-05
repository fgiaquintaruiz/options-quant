package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramServiceSecurityTest {

    private TelegramService service;

    @BeforeEach
    void setUp() {
        service = new TelegramService();
        service.setMasterKey("test-master-key-1234567890");
    }

    // --- generateSecureOrderId ---

    @Test
    @DisplayName("generateSecureOrderId returns non-null non-empty string")
    void generateSecureOrderId_returnsNonEmpty() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(orderId).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("generateSecureOrderId produces different IDs on successive calls")
    void generateSecureOrderId_isUnique() throws InterruptedException {
        String first = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        Thread.sleep(2);
        String second = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("generateSecureOrderId stores orderId in pendingOrders")
    void generateSecureOrderId_storesInPendingOrders() throws Exception {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(getPendingOrders().containsKey(orderId)).isTrue();
    }

    @Test
    @DisplayName("generateSecureOrderId with blank masterKey falls back and still returns an orderId")
    void generateSecureOrderId_blankMasterKey_stillReturnsId() {
        service.setMasterKey("");
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(orderId).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("generateSecureOrderId with null masterKey falls back and still returns an orderId")
    void generateSecureOrderId_nullMasterKey_stillReturnsId() {
        service.setMasterKey(null);
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(orderId).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("generateSecureOrderId with blank masterKey stores orderId in pendingOrders")
    void generateSecureOrderId_blankMasterKey_storesInPendingOrders() throws Exception {
        service.setMasterKey("");
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(getPendingOrders().containsKey(orderId)).isTrue();
    }

    @Test
    @DisplayName("generateSecureOrderId with null masterKey stores orderId in pendingOrders")
    void generateSecureOrderId_nullMasterKey_storesInPendingOrders() throws Exception {
        service.setMasterKey(null);
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(getPendingOrders().containsKey(orderId)).isTrue();
    }

    // --- validateWebhookRequest ---

    @Test
    @DisplayName("validateWebhookRequest returns true for valid orderId and matching details")
    void validateWebhookRequest_valid_returnsTrue() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "CALL", 150.0)).isTrue();
    }

    @Test
    @DisplayName("validateWebhookRequest returns false when orderId is not in pendingOrders")
    void validateWebhookRequest_unknownOrderId_returnsFalse() {
        assertThat(service.validateWebhookRequest("nonexistent_id", "AAPL", "SMA", "CALL", 150.0)).isFalse();
    }

    @Test
    @DisplayName("validateWebhookRequest returns false when orderId is expired (>5 minutes old)")
    void validateWebhookRequest_expiredOrder_returnsFalse() throws Exception {
        String orderId = injectExpiredOrder("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "CALL", 150.0)).isFalse();
    }

    @Test
    @DisplayName("validateWebhookRequest returns false when ticker does not match")
    void validateWebhookRequest_tickerMismatch_returnsFalse() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "TSLA", "SMA", "CALL", 150.0)).isFalse();
    }

    @Test
    @DisplayName("validateWebhookRequest returns false when strategy does not match")
    void validateWebhookRequest_strategyMismatch_returnsFalse() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "EMA", "CALL", 150.0)).isFalse();
    }

    @Test
    @DisplayName("validateWebhookRequest returns false when direction does not match")
    void validateWebhookRequest_directionMismatch_returnsFalse() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "PUT", 150.0)).isFalse();
    }

    @Test
    @DisplayName("validateWebhookRequest returns false when price mismatch exceeds tolerance (>= 0.01)")
    void validateWebhookRequest_priceMismatchBeyondTolerance_returnsFalse() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "CALL", 150.02)).isFalse();
    }

    @Test
    @DisplayName("validateWebhookRequest returns true when price within tolerance (< 0.01)")
    void validateWebhookRequest_priceWithinTolerance_returnsTrue() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "CALL", 150.009)).isTrue();
    }

    @Test
    @DisplayName("validateWebhookRequest without masterKey allows all requests (fallback mode)")
    void validateWebhookRequest_noMasterKey_allowsAll() {
        service.setMasterKey(null);
        assertThat(service.validateWebhookRequest("any-id", "AAPL", "SMA", "CALL", 150.0)).isTrue();
    }

    @Test
    @DisplayName("validateWebhookRequest with blank masterKey allows all requests (fallback mode)")
    void validateWebhookRequest_blankMasterKey_allowsAll() {
        service.setMasterKey("");
        assertThat(service.validateWebhookRequest("any-id", "AAPL", "SMA", "CALL", 150.0)).isTrue();
    }

    @Test
    @DisplayName("validateWebhookRequest consumes orderId — cannot be reused after successful validation")
    void validateWebhookRequest_consumesOrderId_replayPrevented() {
        String orderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "CALL", 150.0)).isTrue();
        assertThat(service.validateWebhookRequest(orderId, "AAPL", "SMA", "CALL", 150.0)).isFalse();
    }

    // --- cleanupOldOrders ---

    @Test
    @DisplayName("cleanupOldOrders removes orders older than 5 minutes when a new order is generated")
    void cleanupOldOrders_removesExpiredOrders() throws Exception {
        String staleOrderId = injectExpiredOrder("AAPL", "SMA", "CALL", 150.0);

        service.generateSecureOrderId("TSLA", "EMA", "PUT", 200.0);

        assertThat(getPendingOrders().containsKey(staleOrderId)).isFalse();
    }

    @Test
    @DisplayName("cleanupOldOrders keeps orders within the 5-minute window")
    void cleanupOldOrders_keepsRecentOrders() throws Exception {
        String freshOrderId = service.generateSecureOrderId("AAPL", "SMA", "CALL", 150.0);

        service.generateSecureOrderId("TSLA", "EMA", "PUT", 200.0);

        assertThat(getPendingOrders().containsKey(freshOrderId)).isTrue();
    }

    // --- Reflection helpers ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConcurrentHashMap getPendingOrders() throws Exception {
        Field field = TelegramService.class.getDeclaredField("pendingOrders");
        field.setAccessible(true);
        return (ConcurrentHashMap) field.get(service);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String injectExpiredOrder(String ticker, String strategy, String direction, double price) throws Exception {
        Field pendingOrdersField = TelegramService.class.getDeclaredField("pendingOrders");
        pendingOrdersField.setAccessible(true);
        ConcurrentHashMap pending = (ConcurrentHashMap) pendingOrdersField.get(service);

        Class<?> pendingOrderClass = null;
        for (Class<?> inner : TelegramService.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("PendingOrder")) {
                pendingOrderClass = inner;
                break;
            }
        }
        assertThat(pendingOrderClass).isNotNull();

        Constructor<?> ctor = pendingOrderClass.getDeclaredConstructor(
                String.class, String.class, String.class, double.class, long.class);
        ctor.setAccessible(true);

        long expiredTimestamp = System.currentTimeMillis() - (6 * 60 * 1000);
        Object expiredOrder = ctor.newInstance(ticker, strategy, direction, price, expiredTimestamp);

        String orderId = expiredTimestamp + "_expiredtest";
        pending.put(orderId, expiredOrder);
        return orderId;
    }
}
