package com.fgiaquinta.optionsquant.services;

import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TelegramService {

    // Extraemos la configuración centralizada del YAML
    private static final String BOT_TOKEN = ConfigLoader.getConfig().telegram.botToken;
    private static final String CHAT_ID = ConfigLoader.getConfig().telegram.chatId;
    private static final String MASTER_KEY = ConfigLoader.getConfig().telegram.masterKey;

    // El host de Cloudflare suele ser estático o puede ir en config.ibkr si lo prefieres
    private static final String CLOUDFLARE_URL = "https://browser-argue-firefox-charged.trycloudflare.com/buy";

    /**
     * Envía una alerta de trading a Telegram con un botón de ejecución.
     */
    public static void sendSignalAlert(String ticker, String type, double entry, double sl, double tp) {
        try {
            String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

            // Construcción de la URL de ejecución para el botón
            String execUrl = String.format("%s?ticker=%s&type=%s&entry=%.2f&sl=%.2f&tp1=%.2f&token=%s",
                    CLOUDFLARE_URL, ticker, type, entry, sl, tp, MASTER_KEY);

            // Formateo del mensaje en Markdown
            String messageText = "🚨 *ALERTA DE ESTRATEGIA* 🚨\n\n" +
                    "📈 *Activo:* " + ticker + "\n" +
                    "🔔 *Señal:* " + type + " (Java Engine)\n" +
                    "💵 *Precio Entrada:* " + entry + "\n" +
                    "--------------------------\n" +
                    "🟢 *Objetivo TP:* " + tp + "\n" +
                    "🔴 *Stop Loss:* " + sl;

            // Payload JSON para Telegram (incluye el botón interactivo)
            String jsonPayload = """
                {
                    "chat_id": "%s",
                    "text": "%s",
                    "parse_mode": "Markdown",
                    "reply_markup": {
                        "inline_keyboard": [[
                            {
                                "text": "🚀 EJECUTAR OPERACIÓN REAL",
                                "url": "%s"
                            }
                        ]]
                    }
                }
                """.formatted(CHAT_ID, messageText.replace("\n", "\\n"), execUrl);

            // Envío asíncrono mediante el cliente HTTP nativo de Java
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            System.out.println("[Telegram] Alerta enviada correctamente para " + ticker);
                        } else {
                            System.err.println("[Telegram] Error al enviar alerta. Código: " + response.statusCode());
                        }
                    });

        } catch (Exception e) {
            System.err.println("❌ Error crítico en TelegramService: " + e.getMessage());
        }
    }
}
