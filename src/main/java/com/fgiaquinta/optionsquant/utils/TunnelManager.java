package com.fgiaquinta.optionsquant.utils;

import com.fgiaquinta.optionsquant.services.TelegramService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;

public class TunnelManager {
    private Process process;

    public void start(String localUrl) {
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("☁️ Starting Cloudflare Tunnel for " + localUrl + "...");
                ProcessBuilder pb = new ProcessBuilder("cloudflared.exe", "tunnel", "--url", localUrl);
                pb.redirectErrorStream(true);
                process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Cloudflare] " + line);
                        if (line.contains(".trycloudflare.com")) {
                            String[] words = line.split("\\s+");
                            for (String word : words) {
                                if (word.startsWith("https://")) {
                                    String url = word.trim();
                                    System.out.println("🔗 Tunnel established: " + url);
                                    TelegramService.setExternalUrl(url);
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ Tunnel Error: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        if (process != null && process.isAlive()) {
            System.out.println("☁️ Closing Cloudflare Tunnel...");
            process.destroy();
        }
    }
}