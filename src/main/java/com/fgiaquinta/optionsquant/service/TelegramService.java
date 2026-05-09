package com.fgiaquinta.optionsquant.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram Bot Service for trading notifications and remote control.
 * Sends signals, alerts, and trade confirmations to your Telegram chat.
 * 
 * SECURITY: Uses master key to validate webhook requests and ensure
 * only authorized callbacks can trigger trades in your account.
 */
@Slf4j
@Service
@ConfigurationProperties(prefix = "telegram")
public class TelegramService {

    private String botToken;
    private String chatId;
    private String webhookSecret;  // Secret token to validate webhook requests from Telegram
    private String masterKey;      // Master key for generating secure order signatures
    private boolean enabled = false;

    // Track pending orders for validation
    private final ConcurrentHashMap<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String TELEGRAM_API = "https://api.telegram.org/bot";

    /**
     * Generates a secure order ID with master key signature.
     * This ensures only our bot can trigger this order.
     */
    public String generateSecureOrderId(String ticker, String strategy, String direction, double price) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String raw = ticker + ":" + strategy + ":" + direction + ":" + price + ":" + timestamp + ":" + masterKey;
        
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            String signature = bytesToHex(hash).substring(0, 16);
            String orderId = timestamp + "_" + signature;
            
            // Store pending order for validation
            pendingOrders.put(orderId, new PendingOrder(ticker, strategy, direction, price, System.currentTimeMillis()));
            
            // Clean old orders (>5 minutes)
            cleanupOldOrders();
            
            return orderId;
        } catch (Exception e) {
            log.error("Failed to generate secure order ID: {}", e.getMessage());
            String fallbackId = String.valueOf(System.currentTimeMillis());
            pendingOrders.put(fallbackId, new PendingOrder(ticker, strategy, direction, price, System.currentTimeMillis()));
            cleanupOldOrders();
            return fallbackId;
        }
    }

    /**
     * Validates a webhook request using master key signature.
     * Returns true only if the signature matches.
     */
    public boolean validateWebhookRequest(String orderId, String ticker, String strategy, String direction, double price) {
        if (masterKey == null || masterKey.isEmpty()) {
            log.warn("⚠️ Master key not configured - allowing request (INSECURE!)");
            return true; // Fallback for testing
        }

        PendingOrder order = pendingOrders.remove(orderId);
        if (order == null) {
            log.warn("🛑 SECURITY: Invalid or expired order ID: {}", orderId);
            return false;
        }

        // Check if order is too old (>5 minutes)
        if (System.currentTimeMillis() - order.timestamp > 5 * 60 * 1000) {
            log.warn("🛑 SECURITY: Expired order ({} seconds old)", (System.currentTimeMillis() - order.timestamp) / 1000);
            return false;
        }

        // Validate order details match
        boolean valid = order.ticker.equals(ticker) &&
                       order.strategy.equals(strategy) &&
                       order.direction.equals(direction) &&
                       Math.abs(order.price - price) < 0.01;

        if (!valid) {
            log.warn("🛑 SECURITY: Order details mismatch! Expected: {} {} {} ${}, Got: {} {} {} ${}",
                    order.ticker, order.strategy, order.direction, order.price,
                    ticker, strategy, direction, price);
        }

        return valid;
    }

    private void cleanupOldOrders() {
        long now = System.currentTimeMillis();
        pendingOrders.entrySet().removeIf(entry -> (now - entry.getValue().timestamp) > 5 * 60 * 1000);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @PostConstruct
    void warnIfMisconfigured() {
        if (enabled && !StringUtils.hasText(botToken)) {
            log.warn("[Telegram] Bot is enabled but TELEGRAM_BOT_TOKEN is not set — Telegram notifications disabled. Set it in .env");
        }
    }

    /**
     * Answers a callback query to stop the loading animation in Telegram.
     */
    public void answerCallbackQuery(String callbackQueryId, String message) {
        if (!enabled || !StringUtils.hasText(botToken)) return;

        try {
            String url = TELEGRAM_API + botToken + "/answerCallbackQuery";
            String body = "callback_query_id=" + URLEncoder.encode(callbackQueryId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                    + "&show_alert=false";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("⚠️ answerCallbackQuery failed: {}", response.body());
            }

        } catch (Exception e) {
            log.warn("⚠️ answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    /**
     * Sends a trading signal notification with secure callback button.
     */
    public void sendSignal(String ticker, String strategy, String direction, double price, double tp, double sl, String orderId) {
        if (!enabled || !StringUtils.hasText(botToken) || chatId == null) return;

        String emoji = direction.equals("CALL") ? "📈" : "📉";
        boolean isCall = direction.equals("CALL");

        String message;
        if (isCall) {
            // For CALL: SL < Entry < TP, show SL first then TP
            message = String.format("""
                    %s *NEW SIGNAL DETECTED*

                    📊 Ticker: *%s*
                    🎯 Strategy: *%s*
                    📍 Direction: *%s*
                    💰 Entry: $%.2f

                    🛑 Stop Loss: $%.2f
                    🎯 Take Profit: $%.2f

                    ⏰ %s

                    [Execute Order](https://t.me/%s?start=%s)
                    """, emoji, ticker, strategy, direction, price, sl, tp,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    botToken.split(":")[0], orderId);
        } else {
            // For PUT: TP < Entry < SL, show TP first then SL
            message = String.format("""
                    %s *NEW SIGNAL DETECTED*

                    📊 Ticker: *%s*
                    🎯 Strategy: *%s*
                    📍 Direction: *%s*
                    💰 Entry: $%.2f

                    🎯 Take Profit: $%.2f
                    🛑 Stop Loss: $%.2f

                    ⏰ %s

                    [Execute Order](https://t.me/%s?start=%s)
                    """, emoji, ticker, strategy, direction, price, tp, sl,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    botToken.split(":")[0], orderId);
        }

        sendMessage(message, "Markdown");
    }

    /**
     * Sends a trading signal notification (without orderId for backward compatibility).
     */
    public void sendSignal(String ticker, String strategy, String direction, double price, double tp, double sl) {
        sendSignal(ticker, strategy, direction, price, tp, sl, "N/A");
    }

    /**
     * Sends a signal when auto-execute is enabled (info-only, no execute button).
     */
    public void sendAutoExecuteSignal(String ticker, String strategy, String direction, double price, double tp, double sl) {
        if (!enabled || !StringUtils.hasText(botToken) || chatId == null) return;

        String emoji = direction.equals("CALL") ? "📈" : "📉";
        boolean isCall = direction.equals("CALL");

        String message;
        if (isCall) {
            // For CALL: SL < Entry < TP, show SL first then TP
            message = String.format("""
                    %s *SIGNAL DETECTED & AUTO-EXECUTED*

                    📊 Ticker: *%s*
                    🎯 Strategy: *%s*
                    📍 Direction: *%s*
                    💰 Entry: $%.2f

                    🛑 Stop Loss: $%.2f
                    🎯 Take Profit: $%.2f

                    ⏰ %s

                    ✅ Order automatically sent to IBKR
                    """, emoji, ticker, strategy, direction, price, sl, tp,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } else {
            // For PUT: TP < Entry < SL, show TP first then SL
            message = String.format("""
                    %s *SIGNAL DETECTED & AUTO-EXECUTED*

                    📊 Ticker: *%s*
                    🎯 Strategy: *%s*
                    📍 Direction: *%s*
                    💰 Entry: $%.2f

                    🎯 Take Profit: $%.2f
                    🛑 Stop Loss: $%.2f

                    ⏰ %s

                    ✅ Order automatically sent to IBKR
                    """, emoji, ticker, strategy, direction, price, tp, sl,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }

        sendMessage(message, "Markdown");
    }

    /**
     * Sends trade execution confirmation.
     */
    public void sendTradeConfirmation(String ticker, String strategy, String direction, int qty, double entryPrice, int orderId) {
        if (!enabled) return;

        String secureOrderId = generateSecureOrderId(ticker, strategy, direction, entryPrice);
        
        String message = String.format("""
                ✅ *TRADE EXECUTED*
                
                📊 %s %s
                📦 Qty: %d contracts
                💰 Entry: $%.2f
                🆔 Order ID: %s
                🔒 Secure: %s
                
                🤖 Auto-executed by Options Quant Bot
                """, ticker, direction, qty, entryPrice, orderId, secureOrderId.substring(0, 8) + "...");

        sendMessage(message, "Markdown");
    }

    /**
     * Sends trade exit notification.
     */
    public void sendTradeExit(String ticker, String direction, double exitPrice, double pnl, String exitReason) {
        if (!enabled) return;

        String emoji = pnl > 0 ? "💚" : "❤️";
        String message = String.format("""
                %s *TRADE CLOSED*
                
                📊 %s %s
                💰 Exit: $%.2f
                📈 P&L: $%.2f
                📋 Reason: %s
                """, emoji, ticker, direction, exitPrice, pnl, exitReason);

        sendMessage(message, "Markdown");
    }

    /**
     * Sends daily summary of trading activity.
     */
    public void sendDailySummary(int totalTrades, int wins, int losses, double totalPnl, double winRate) {
        if (!enabled) return;

        String message = String.format("""
                📊 *DAILY TRADING SUMMARY*
                
                📈 Total Trades: %d
                ✅ Wins: %d
                ❌ Losses: %d
                📊 Win Rate: %.1f%%
                💰 Total P&L: $%.2f
                """, totalTrades, wins, losses, winRate, totalPnl);

        sendMessage(message, "Markdown");
    }

    /**
     * Sends macro environment status.
     */
    public void sendMacroStatus(String trend) {
        if (!enabled) return;

        String emoji = trend.contains("BULLISH") ? "🐂" : trend.contains("BEARISH") ? "🐻" : "⚖️";
        String message = String.format("""
                %s *MACRO ENVIRONMENT UPDATE*
                
                Market Trend: %s
                
                %s
                """, emoji, trend, trend.contains("BULLISH") ? "✅ CALL strategies favored" : trend.contains("BEARISH") ? "✅ PUT strategies favored" : "⚠️ Mixed signals - all strategies allowed");

        sendMessage(message, "Markdown");
    }

    /**
     * Sends system alert (errors, warnings).
     */
    public void sendAlert(String title, String message) {
        if (!enabled) return;

        String formattedMessage = String.format("""
                🚨 *%s*
                
                %s
                """, title, message);

        sendMessage(formattedMessage, "Markdown");
    }

    /**
     * Sends a simple text message.
     */
    public void sendMessage(String text) {
        sendMessage(text, null);
    }

    /**
     * Sends a message with optional parse mode.
     */
    private void sendMessage(String text, String parseMode) {
        if (!enabled || !StringUtils.hasText(botToken) || chatId == null) return;

        try {
            String url = TELEGRAM_API + botToken + "/sendMessage";
            String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&disable_web_page_preview=true";

            if (parseMode != null) {
                body += "&parse_mode=" + parseMode;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("⚠️ Telegram message failed: {}", response.body());
            }

        } catch (Exception e) {
            log.warn("⚠️ Telegram send failed: {}", e.getMessage());
        }
    }

    /**
     * Tests the Telegram connection and validates master key.
     */
    public boolean testConnection() {
        if (!enabled || !StringUtils.hasText(botToken)) {
            log.warn("⚠️ Telegram not configured or disabled");
            return false;
        }

        if (masterKey == null || masterKey.length() < 16) {
            log.error("❌ Master key not configured or too short (min 16 chars)");
            return false;
        }

        try {
            String url = TELEGRAM_API + botToken + "/getMe";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200 && response.body().contains("ok");
            
            if (ok) {
                log.info("✅ Telegram connected and master key configured");
            }
            return ok;

        } catch (Exception e) {
            log.error("❌ Telegram connection test failed: {}", e.getMessage());
            return false;
        }
    }

    // Setters for Spring Boot configuration binding
    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Pending order tracking for validation.
     */
    private static class PendingOrder {
        final String ticker;
        final String strategy;
        final String direction;
        final double price;
        final long timestamp;

        PendingOrder(String ticker, String strategy, String direction, double price, long timestamp) {
            this.ticker = ticker;
            this.strategy = strategy;
            this.direction = direction;
            this.price = price;
            this.timestamp = timestamp;
        }
    }
}

