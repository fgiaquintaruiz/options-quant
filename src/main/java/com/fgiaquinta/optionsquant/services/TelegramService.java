package com.fgiaquinta.optionsquant.services;

import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TelegramService {
    private static final String BOT_TOKEN = ConfigLoader.getConfig().telegram.botToken;
    private static final String CHAT_ID = ConfigLoader.getConfig().telegram.chatId;
    private static String externalUrl = "http://localhost:8080";

    public static void setExternalUrl(String url) {
        externalUrl = url;
        System.out.println("🌐 Cloudflare Tunnel Online: " + url);
    }

    public static void sendSimpleMessage(String text) {
        String safeText = text.replace("\"", "\\\"").replace("\n", "\\n");
        String json = String.format("{\"chat_id\": \"%s\", \"text\": \"%s\", \"parse_mode\": \"Markdown\"}", CHAT_ID, safeText);
        sendJsonPayload(json);
    }

    public static void sendSignalConfirmation(String ticker, String strategy, double price, double tp, double sl, int qty) {
        boolean isCall = strategy.toUpperCase().contains("CALL") || strategy.toUpperCase().startsWith("C");
        String side = isCall ? "CALL" : "PUT";
        String callbackUrl = String.format("%s/execute?ticker=%s&side=%s&qty=%d&lmt=%.2f&tp=%.2f&sl=%.2f",
                externalUrl, ticker, side, qty, price, tp, sl);

        String jsonPayload = String.format("""
            {
                "chat_id": "%s",
                "text": "🎯 *SIGNAL DETECTED*\\n*Ticker:* %s\\n*Strategy:* %s\\n*Entry:* %.2f",
                "parse_mode": "Markdown",
                "reply_markup": { "inline_keyboard": [[ { "text": "🚀 EXECUTE", "url": "%s" } ]] }
            }
            """, CHAT_ID, ticker, strategy, price, callbackUrl);

        sendJsonPayload(jsonPayload);
    }

    private static void sendJsonPayload(String jsonPayload) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}