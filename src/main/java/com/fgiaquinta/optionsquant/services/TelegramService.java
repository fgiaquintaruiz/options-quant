package com.fgiaquinta.optionsquant.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TelegramService {

    private static final String BOT_TOKEN = "REDACTED_TELEGRAM_BOT_TOKEN";
    private static final String CHAT_ID = "REDACTED_CHAT_ID";
    private static final String CLOUDFLARE_HOST = "https://browser-argue-firefox-charged.trycloudflare.com";
    private static final String CLOUDFLARE_URL = CLOUDFLARE_HOST + "/buy";
    private static final String MASTER_KEY = System.getenv("OPTIONSQUANT_MASTER_KEY");
    
    // Sends the strategy alert to Telegram with action buttons
    public static void sendSignalAlert(String ticker, String type, double entry, double sl, double tp1, double tp2) {
        try {
            String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

            String execUrl = String.format("%s?ticker=%s&type=%s&entry=%.2f&sl=%.2f&tp1=%.2f&tp2=%.2f&token=%s",
                    CLOUDFLARE_URL, ticker, type, entry, sl, tp1, tp2, MASTER_KEY);

            String messageText = "🚨 *ALERTA DE ESTRATEGIA* 🚨\n\n" +
                    "📈 *Activo:* " + ticker + "\n" +
                    "🔔 *Señal:* " + type + " (Java Native Engine)\n" +
                    "💵 *Entrada:* " + entry + "\n" +
                    "--------------------------\n" +
                    "🟡 *TP1:* " + tp1 + "\n" +
                    "🟢 *TP2:* " + tp2 + "\n" +
                    "🔴 *Stop Loss:* " + sl;

            String jsonPayload = """
                {
                    "chat_id": "%s",
                    "text": "%s",
                    "parse_mode": "Markdown",
                    "reply_markup": {
                        "inline_keyboard": [[
                            {
                                "text": "🚀 EJECUTAR OPERACIÓN",
                                "url": "%s"
                            }
                        ]]
                    }
                }
                """.formatted(CHAT_ID, messageText.replace("\n", "\\n"), execUrl);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            // Fire and forget asynchronous call
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> System.out.println("[Telegram] Status: " + response.statusCode()));

        } catch (Exception e) {
            System.err.println("Error sending Telegram alert: " + e.getMessage());
        }
    }
}