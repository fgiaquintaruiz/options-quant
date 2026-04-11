package com.fgiaquinta.optionsquant.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class MarketTimeUtils {

    // Single Source of Truth for timezone: New York Market Time
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    // CAMBIO: Usamos un formato limpio y legible (ej: 2026-03-24 09:30:00)
    public static final DateTimeFormatter CSV_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Converts the IBKR date string into a ZonedDateTime strictly bound to MARKET_ZONE.
     * Prevents double timezone shifting.
     */
    public static ZonedDateTime parseIbkrDate(String ibkrDateStr) {
        // Handle Unix epoch timestamp format (e.g., from historicalDataUpdate or formatDate=2)
        if (!ibkrDateStr.contains(" ")) {
            long timestamp = Long.parseLong(ibkrDateStr);
            return ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), MARKET_ZONE);
        }

        // Handle string date format (e.g., from historicalData with formatDate=1)
        // IBKR usually sends "yyyyMMdd  HH:mm:ss" (double space) or with timezone "yyyyMMdd HH:mm:ss US/Eastern"
        String cleanDateStr = ibkrDateStr.replace("  ", " ").trim();

        if (cleanDateStr.length() > 17) {
            // Extract only "yyyyMMdd HH:mm:ss" to discard external timezone identifiers
            cleanDateStr = cleanDateStr.substring(0, 17);
        }

        DateTimeFormatter parser = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
        LocalDateTime localDate = LocalDateTime.parse(cleanDateStr, parser);

        // Force the timezone to NY without shifting the hours
        return localDate.atZone(MARKET_ZONE);
    }
}
