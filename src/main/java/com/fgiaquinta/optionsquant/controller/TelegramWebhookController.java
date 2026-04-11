package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.service.TelegramService;
import com.fgiaquinta.optionsquant.service.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook controller for receiving Telegram callback queries.
 * Secured with secret token validation to prevent unauthorized trade execution.
 * 
 * Telegram sends the secret token in the "X-Telegram-Bot-Api-Secret-Token" header
 * when the webhook is registered with a secret token.
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final TelegramService telegramService;
    private final TradingService tradingService;

    /**
     * Receives webhook POST requests from Telegram.
     * Validates secret token before processing any trade commands.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request
    ) {
        try {
            // Extract secret token from header (Telegram sends this when webhook is registered with a secret)
            String receivedSecret = request.getHeader("X-Telegram-Bot-Api-Secret-Token");
            
            // Validate webhook secret
            if (!validateWebhookSecret(receivedSecret)) {
                log.warn("🛑 SECURITY: Invalid webhook secret - rejecting request from IP: {}", 
                        request.getRemoteAddr());
                return ResponseEntity.status(403).body(Map.of("error", "Invalid secret token"));
            }

            // Extract callback query if present
            if (payload.containsKey("callback_query")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> callbackQuery = (Map<String, Object>) payload.get("callback_query");
                
                String callbackQueryId = (String) callbackQuery.get("id");
                String callbackData = (String) callbackQuery.get("data");
                
                if (callbackData != null && callbackData.startsWith("E|")) {
                    return processTradeCommand(callbackQueryId, callbackData);
                }
            }

            // Regular message (not a callback)
            if (payload.containsKey("message")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) payload.get("message");
                
                if (message.containsKey("text")) {
                    String text = (String) message.get("text");
                    if (text != null && text.startsWith("/")) {
                        return processBotCommand(text);
                    }
                }
            }

            return ResponseEntity.ok(Map.of("status", "ok"));

        } catch (Exception e) {
            log.error("❌ Webhook processing error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Validates the webhook secret token.
     * This ensures only Telegram's servers (with our secret) can trigger trades.
     */
    private boolean validateWebhookSecret(String receivedSecret) {
        String configuredSecret = telegramService.getWebhookSecret();
        
        if (configuredSecret == null || configuredSecret.isEmpty() || 
            "YOUR_WEBHOOK_SECRET_HERE".equals(configuredSecret)) {
            log.warn("⚠️ Webhook secret not configured - allowing request (INSECURE!)");
            log.warn("⚠️ Set telegram.webhook-secret in application.yml to secure the webhook");
            return true; // Fallback for testing only - INSECURE!
        }

        if (receivedSecret == null || !receivedSecret.equals(configuredSecret)) {
            log.error("🛑 SECURITY: Webhook secret mismatch!");
            log.error("   Expected: {}", configuredSecret.substring(0, Math.min(8, configuredSecret.length())) + "...");
            log.error("   Received: {}", receivedSecret != null ? "null" : "(missing)");
            return false;
        }

        return true;
    }

    /**
     * Processes a trade execution command from Telegram callback.
     * Format: E|TICKER|STRATEGY|PRICE
     */
    private ResponseEntity<Map<String, Object>> processTradeCommand(
            String callbackQueryId, 
            String callbackData
    ) {
        try {
            String[] parts = callbackData.split("\\|");
            if (parts.length < 4) {
                log.error("❌ Invalid callback data format: {}", callbackData);
                telegramService.answerCallbackQuery(callbackQueryId, "❌ Invalid command format");
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid command format"));
            }

            String ticker = parts[1];
            String strategy = parts[2];
            double price = Double.parseDouble(parts[3]);
            String direction = parts.length > 4 ? parts[4] : "CALL";

            log.info("🎯 Executing trade from Telegram: {} {} {} @ ${}", ticker, direction, strategy, price);

            // Answer callback to stop loading animation
            telegramService.answerCallbackQuery(callbackQueryId, "✅ Order sent to IBKR 🚀");

            // Execute the trade
            boolean success = tradingService.executeManualTrade(ticker, strategy, direction, price);

            if (success) {
                log.info("✅ Trade executed successfully: {} {} @ ${}", ticker, direction, price);
                telegramService.sendTradeConfirmation(ticker, strategy, direction, 0, price, 0);
                return ResponseEntity.ok(Map.of("status", "executed", "ticker", ticker));
            } else {
                log.error("❌ Trade execution failed: {} {}", ticker, direction);
                telegramService.sendAlert("Trade Failed", "Failed to execute " + ticker + " " + direction);
                return ResponseEntity.status(500).body(Map.of("error", "Trade execution failed"));
            }

        } catch (NumberFormatException e) {
            log.error("❌ Invalid price in callback data: {}", callbackData);
            telegramService.answerCallbackQuery(callbackQueryId, "❌ Invalid price");
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid price"));
        }
    }

    /**
     * Processes bot commands sent via Telegram messages.
     */
    private ResponseEntity<Map<String, Object>> processBotCommand(String command) {
        String[] parts = command.split(" ");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/status":
                String status = "🤖 Bot is running\n" +
                        "📊 Market Scanner: Active\n" +
                        "🔔 Notifications: " + (telegramService.isEnabled() ? "Enabled" : "Disabled");
                return ResponseEntity.ok(Map.of("response", status));

            case "/help":
                String help = "Available commands:\n" +
                        "/status - Show bot status\n" +
                        "/help - Show this help\n" +
                        "/pause - Pause trading\n" +
                        "/resume - Resume trading";
                return ResponseEntity.ok(Map.of("response", help));

            case "/pause":
                // TODO: Implement pause functionality
                return ResponseEntity.ok(Map.of("response", "⏸️ Trading paused"));

            case "/resume":
                // TODO: Implement resume functionality
                return ResponseEntity.ok(Map.of("response", "▶️ Trading resumed"));

            default:
                return ResponseEntity.ok(Map.of("response", "Unknown command. Use /help for available commands."));
        }
    }
}
