package com.fgiaquinta.optionsquant.infrastructure;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.domain.Candle;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Handles IBKR protocol callbacks and parses raw data into domain objects.
 * Separated from IbkrService to follow Composition Over Inheritance and SRP.
 */
@Slf4j
public final class IbkrCallbackHandler extends DefaultEWrapper {

    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    // IBKR sends: "20260325 09:30:00 US/Eastern" (one space, with timezone)
    private static final DateTimeFormatter INTRADAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final Set<String> ISLAND_EXCHANGE_STOCKS = Set.of(
            "AAPL", "MSFT", "AMZN", "NVDA", "TSLA", "META", "AMD");
    // Known exchange routing for ETFs (primary exchange for SMART routing)
    private static final Map<String, String> KNOWN_EXCHANGES = Map.of(
            "SPY", "ARCA",
            "QQQ", "NASDAQ",
            "DIA", "NYSE",
            "IWM", "NYSE"
    );

    private final Consumer<BarEvent> onBar;
    private final Consumer<RequestCompleteEvent> onComplete;
    private final Consumer<ErrorEvent> onError;
    /** Receives TWS {@code nextValidId} (first usable order id) — not the API client id. */
    private final Consumer<Integer> onNextValidId;
    private final Consumer<ExecutionEvent> onExecution;  // NEW: callback for trade fills

    public IbkrCallbackHandler(Consumer<BarEvent> onBar, Consumer<RequestCompleteEvent> onComplete,
                               Consumer<ErrorEvent> onError, Consumer<Integer> onNextValidId) {
        this(onBar, onComplete, onError, onNextValidId, null);
    }

    public IbkrCallbackHandler(Consumer<BarEvent> onBar, Consumer<RequestCompleteEvent> onComplete,
                               Consumer<ErrorEvent> onError, Consumer<Integer> onNextValidId,
                               Consumer<ExecutionEvent> onExecution) {
        this.onBar = onBar;
        this.onComplete = onComplete;
        this.onError = onError;
        this.onNextValidId = onNextValidId;
        this.onExecution = onExecution;
    }

    // ===== Factory methods =====

    public static Contract createStockContract(String ticker) {
        Contract contract = new Contract();
        contract.symbol(ticker);
//        contract.conid(1);
        contract.secType("STK");
        contract.currency("USD");
        contract.exchange("SMART");
        
        // Set known primary exchange for ETFs
//        String knownExch = KNOWN_EXCHANGES.get(ticker);
//        if (knownExch != null) {
//            contract.primaryExch(knownExch);
//        } else if (ISLAND_EXCHANGE_STOCKS.contains(ticker)) {
//            contract.primaryExch("ISLAND");
//        }
        // For unknown tickers, let SMART routing figure it out (no primaryExch set)
        
        return contract;
    }

    public static Candle parseBar(com.ib.client.Bar bar) {
        String dateStr = bar.time();
        ZonedDateTime timestamp;

        if (dateStr.length() == 8) {
            // Daily bar: "20260408"
            LocalDate date = LocalDate.parse(dateStr, DAILY_FMT);
            timestamp = date.atTime(16, 0).atZone(NY_ZONE);
        } else {
            // Intraday bar: "20260325 09:30:00 US/Eastern"
            timestamp = ZonedDateTime.parse(dateStr, INTRADAY_FMT);
        }

        return new Candle(
                timestamp,
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                extractVolume(bar)
        );
    }

    // ===== EWrapper callbacks =====

    @Override
    public void connectAck() {
        log.info("IBKR connection acknowledged");
    }

    @Override
    public void nextValidId(int orderId) {
        log.info("IBKR ready. Next valid order ID: {}", orderId);
        onNextValidId.accept(orderId);
    }

    @Override
    public void historicalData(int reqId, com.ib.client.Bar bar) {
        try {
            Candle candle = parseBar(bar);
            onBar.accept(new BarEvent(reqId, candle));
        } catch (Exception e) {
            log.error("Error parsing bar for reqId={}: {}", reqId, e.getMessage());
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        log.debug("Historical data complete: reqId={}, period={}-{}", reqId, startDateStr, endDateStr);
        onComplete.accept(new RequestCompleteEvent(reqId, startDateStr, endDateStr));
    }

    @Override
    public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        // Skip informational messages
        if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;

        // Skip error 366 - it's a spurious cleanup message that arrives AFTER historicalDataEnd
        // The data has already been received successfully
        if (errorCode == 366) {
            log.debug("Ignoring spurious error 366 for reqId={} (data already received)", id);
            return;
        }

        // Skip error 162 when it's a cancellation message (we intentionally cancelled after receiving data)
        if (errorCode == 162 && errorMsg != null && errorMsg.contains("API historical data query cancelled")) {
            log.debug("Ignoring expected cancellation error 162 for reqId={} (intentionally cancelled)", id);
            return;
        }

        log.error("🚨 TWS error for orderId={}: code={}, msg={}", id, errorCode, errorMsg);
        onError.accept(new ErrorEvent(id, errorCode, errorMsg));
    }

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining,
                            double avgFillPrice, long permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {
        log.info("📋 TWS orderStatus: orderId={}, status={}, filled={}, remaining={}",
                orderId, status, filled, remaining);
    }

    @Override
    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        log.info("📋 TWS openOrder: orderId={}, symbol={}, action={}, qty={}, state={}",
                orderId, contract.symbol(), order.action(), order.totalQuantity(), orderState.status());
    }

    @Override
    public void contractDetails(int reqId, ContractDetails details) {
        log.debug("Contract details for reqId={}: symbol={}", reqId, details.contract().symbol());
    }

    @Override
    public void contractDetailsEnd(int reqId) {
        log.debug("Contract details end for reqId={}", reqId);
    }

    // ===== Private helpers =====

    private static long extractVolume(com.ib.client.Bar bar) {
        var vol = bar.volume();
        if (vol == null) return 0L;
        var val = vol.value();
        if (val == null) return 0L;
        return val.longValue();
    }

    // ===== Event records =====

    public record BarEvent(int reqId, Candle candle) {}
    public record RequestCompleteEvent(int reqId, String startDate, String endDate) {}
    public record ErrorEvent(int id, int code, String message) {
        public boolean isHistoricalDataError() {
            return code == 366 || code == 162 || code == 200;
        }
    }
    public record ExecutionEvent(String ticker, String side, double price, int quantity, ZonedDateTime timestamp) {}
}
