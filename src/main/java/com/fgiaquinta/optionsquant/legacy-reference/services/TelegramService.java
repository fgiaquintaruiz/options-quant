package com.fgiaquinta.optionsquant.services;

import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class TelegramService {
    private static final String BOT_TOKEN = ConfigLoader.getConfig().getString("telegram", "botToken");
    private static final String CHAT_ID = ConfigLoader.getConfig().getString("telegram", "chatId");
    private static String externalUrl = "http://localhost:9090";

    public static String getExternalUrl() {
        return externalUrl;
    }

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
        String direction = isCall ? "📈 *CALL*" : "📉 *PUT*";
        String emoji = isCall ? "🟢" : "🔴";

        String message = String.format(
                "%s *SEÑAL DETECTADA* %s\n\n" +
                        "🏦 *Ticker:* %s\n" +
                        "🧠 *Estrategia:* %s\n" +
                        "🎯 *Dirección:* %s\n\n" +
                        "💰 *Entrada:* $%.2f\n" +
                        "🛑 *Stop Loss:* $%.2f\n" +
                        "✅ *Take Profit:* $%.2f\n" +
                        "📦 *Tamaño Posición:* %d contratos",
                emoji, emoji, ticker, strategy, direction, price, sl, tp, qty
        );

        String safeText = message.replace("\"", "\\\"").replace("\n", "\\n");

        // 👉 AQUÍ ESTÁ LA MAGIA: Usamos el nuevo teclado con callback_data
        String keyboard = buildInlineKeyboard(ticker, strategy, price);

        String json = String.format("{\"chat_id\": \"%s\", \"text\": \"%s\", \"parse_mode\": \"Markdown\", \"reply_markup\": %s}",
                CHAT_ID, safeText, keyboard);

        sendJsonPayload(json);
    }

    // 👉 NUEVO: Construimos un botón silencioso (callback_data) en lugar de un enlace (url)
    private static String buildInlineKeyboard(String ticker, String strategy, double price) {
        // Formato comprimido para no pasar de 64 bytes: E|TICKER|STRAT|PRICE
        // Ej: E|MSFT|C1SqueezeCallStrategy|365.85
        String callbackData = String.format("E|%s|%s|%.2f", ticker, strategy, price);

        // Si la estrategia es muy larga y pasa de 64 bytes, Telegram falla. Validamos por si acaso:
        if (callbackData.length() > 64) {
            System.err.println("⚠️ Warning: callback_data excede 64 bytes. Recortando estrategia...");
            callbackData = String.format("E|%s|%s|%.2f", ticker, strategy.substring(0, 15), price);
        }

        return "{\"inline_keyboard\": [[{\"text\": \"⚡ Ejecutar Orden\", \"callback_data\": \"" + callbackData + "\"}]]}";
    }

    private static void sendJsonPayload(String jsonPayload) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            System.err.println("❌ Telegram API Error: " + response.body());
                        }
                    });
        } catch (Exception e) {
            System.err.println("❌ Error enviando mensaje a Telegram: " + e.getMessage());
        }
    }

    // 👉 NUEVO: Registra el Webhook en la API de Telegram al arrancar
    // 👉 NUEVO: Registra el Webhook de forma robusta y lee el error exacto
    public void registerWebhook(String tunnelUrl) {
        try {
            // 1. Limpiar espacios extra y evitar barras dobles
            String cleanTunnel = tunnelUrl.trim();
            if (cleanTunnel.endsWith("/")) {
                cleanTunnel = cleanTunnel.substring(0, cleanTunnel.length() - 1);
            }
            String webhookUrl = cleanTunnel + "/webhook";

            // 2. Codificar la URL (URL-Encode) para que Telegram no arroje HTTP 400
            String encodedWebhookUrl = java.net.URLEncoder.encode(webhookUrl, "UTF-8");

            // 3. Limpiar también el BOT_TOKEN por si acaso tiene espacios en el config.yaml
            String cleanToken = BOT_TOKEN.replace("\"", "").trim();
            String urlStr = "https://api.telegram.org/bot" + cleanToken + "/setWebhook?url=" + encodedWebhookUrl;

            System.out.println("🔄 Intentando enlazar Telegram con: " + webhookUrl);

            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                System.out.println("✅ Webhook de Telegram registrado con éxito automático.");
            } else {
                // 👉 LA MAGIA: Si falla, leer el mensaje de error EXACTO que nos envía Telegram
                java.io.InputStream errorStream = conn.getErrorStream();
                String errorResponse = "";
                if (errorStream != null) {
                    errorResponse = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                System.err.println("❌ Telegram rechazó el Webhook (HTTP " + responseCode + ") -> Detalles: " + errorResponse);
            }
        } catch (Exception e) {
            System.err.println("❌ Excepción en registerWebhook: " + e.getMessage());
        }
    }

    // 👉 NUEVO: Responde al botón para que deje de cargar el "relojito"
    public void answerCallbackQuery(String callbackQueryId, String popupMessage) {
        try {
            String urlStr = "https://api.telegram.org/bot" + BOT_TOKEN + "/answerCallbackQuery";

            String urlParameters = "callback_query_id=" + callbackQueryId + "&text=" +
                    java.net.URLEncoder.encode(popupMessage, "UTF-8");

            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            conn.getResponseCode();
        } catch (Exception e) {
            System.err.println("Error cerrando CallbackQuery: " + e.getMessage());
        }
    }
}