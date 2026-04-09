package com.fgiaquinta.optionsquant.strategy.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class MarketTimeUtils {

    // Single Source of Truth for timezone: New York Market Time
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    public static final DateTimeFormatter CSV_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Converts the IBKR date string into a ZonedDateTime strictly bound to MARKET_ZONE.
     */
    public static ZonedDateTime parseIbkrDate(String ibkrDateStr) {
        if (!ibkrDateStr.contains(" ")) {
            long timestamp = Long.parseLong(ibkrDateStr);
            return ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), MARKET_ZONE);
        }

        String cleanDateStr = ibkrDateStr.replace("  ", " ").trim();

        if (cleanDateStr.length() > 17) {
            cleanDateStr = cleanDateStr.substring(0, 17);
        }

        DateTimeFormatter parser = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
        LocalDateTime localDate = LocalDateTime.parse(cleanDateStr, parser);

        return localDate.atZone(MARKET_ZONE);
    }
}
