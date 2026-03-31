package com.fgiaquinta.optionsquant.services;

import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TelegramService {

    private TelegramService() {
        /* This utility class should not be instantiated */
    }


    // Centralized configuration properties
    private static final String BOT_TOKEN = ConfigLoader.getConfig().telegram.botToken;
    private static final String CHAT_ID = ConfigLoader.getConfig().telegram.chatId;

    /**
     * Sends a standard text message without any interactive buttons.
     * Used for system alerts, errors, and trade closure reports.
     */
    public static void sendSimpleMessage(String text) {
        // Sanitize the text to prevent JSON payload formatting errors
        String safeText = text.replace("\"", "\\\"").replace("\n", "\\n");

        String jsonPayload = String.format("""
            {
                "chat_id": "%s",
                "text": "%s",
                "parse_mode": "Markdown"
            }
            """, CHAT_ID, safeText);

        sendJsonPayload(jsonPayload);
    }

    /**
     * Sends the final financial report when a Take Profit or Stop Loss executes.
     */
    public static void sendTradeClosedAlert(String ticker, String strategy, double price, double pnl, double comm) {
        double net = pnl - comm;
        String emoji = net > 0 ? "💰" : "📉";

        // Using single asterisks for Markdown bold in Telegram
        String msg = String.format("""
            %s *TRADE CLOSED* %s
            📌 *Asset:* %s (%s)
            🏁 *Exit Price:* %.2f
            --------------------------
            💵 *Gross PnL:* %.2f USD
            💸 *Commission:* %.2f USD
            💎 *NET PROFIT:* %.2f USD
            --------------------------""",
                emoji, emoji, ticker, strategy, price, pnl, comm, net);

        sendSimpleMessage(msg);
    }

    /**
     * Sends a trade signal with an interactive callback button.
     * Tapping the button triggers the native Java Webhook Handler without opening a browser.
     */
    public static void sendSignalConfirmation(String ticker, String strategy, double entry, double tp, double sl, int qty) {
        String messageText = String.format("""
            🚨 *TRADE SIGNAL DETECTED* 🚨
            
            📌 *Asset:* %s
            🧠 *Strategy:* %s
            💵 *Entry Price:* %.2f
            🎯 *Take Profit:* %.2f
            🛡️ *Stop Loss:* %.2f
            📦 *Quantity:* %d
            
            Waiting for manual execution approval...""",
                ticker, strategy, entry, tp, sl, qty);

        // The callback_data contains the exact parameters your WebhookHandler needs
        String callbackData = String.format("EXEC_%s_%s_%.2f_%.2f_%.2f_%d", ticker, strategy, entry, tp, sl, qty);

        String jsonPayload = String.format("""
            {
                "chat_id": "%s",
                "text": "%s",
                "parse_mode": "Markdown",
                "reply_markup": {
                    "inline_keyboard": [[
                        {
                            "text": "🚀 EXECUTE REAL TRADE",
                            "callback_data": "%s"
                        }
                    ]]
                }
            }
            """, CHAT_ID, messageText.replace("\n", "\\n"), callbackData);

        sendJsonPayload(jsonPayload);
    }

    /**
     * Core utility to dispatch the JSON payload to the Telegram API.
     */
    private static void sendJsonPayload(String jsonPayload) {
        try {
            // 1. Define the target URL
            String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

            // 2. Build the Request (Ensure this variable name matches the one used in sendAsync)
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            // 3. Dispatch Asynchronously
            HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            System.out.println("✅ Telegram message dispatched successfully.");
                        } else {
                            System.err.println("❌ Telegram API Error: " + response.statusCode() + " - " + response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("❌ Network Error sending to Telegram: " + ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            System.err.println("❌ Critical Error in Telegram Service: " + e.getMessage());
            e.printStackTrace();
        }
    }
}