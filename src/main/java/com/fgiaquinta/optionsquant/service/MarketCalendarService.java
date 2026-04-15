package com.fgiaquinta.optionsquant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/**
 * US Stock Market calendar awareness.
 * 
 * Knows about:
 * - Regular market hours: Mon-Fri 9:30 AM - 4:00 PM ET
 * - Extended hours: Pre-market 4:00-9:30 AM, After-hours 4:00-8:00 PM ET
 * - Weekends: No trading Sat-Sun
 * - US market holidays (NYSE closures)
 * 
 * Used to avoid unnecessary IBKR data downloads when no new candles can exist.
 */
@Slf4j
@Service
public class MarketCalendarService {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Market hours in ET
    private static final LocalTime PRE_MARKET_OPEN = LocalTime.of(4, 0);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime AFTER_HOURS_CLOSE = LocalTime.of(20, 0);

    // US Market Holidays (2025-2027)
    // These are NYSE observed holidays when markets are closed
    private static final Set<LocalDate> MARKET_HOLIDAYS = Set.of(
            // 2025
            LocalDate.of(2025, 1, 1),   // New Year's Day
            LocalDate.of(2025, 1, 20),  // MLK Day
            LocalDate.of(2025, 2, 17),  // Presidents' Day
            LocalDate.of(2025, 4, 18),  // Good Friday
            LocalDate.of(2025, 5, 26),  // Memorial Day
            LocalDate.of(2025, 6, 19),  // Juneteenth
            LocalDate.of(2025, 7, 4),   // Independence Day
            LocalDate.of(2025, 9, 1),   // Labor Day
            LocalDate.of(2025, 11, 27), // Thanksgiving
            LocalDate.of(2025, 12, 25), // Christmas
            // 2026
            LocalDate.of(2026, 1, 1),   // New Year's Day
            LocalDate.of(2026, 1, 19),  // MLK Day
            LocalDate.of(2026, 2, 16),  // Presidents' Day
            LocalDate.of(2026, 4, 3),   // Good Friday
            LocalDate.of(2026, 5, 25),  // Memorial Day
            LocalDate.of(2026, 6, 19),  // Juneteenth
            LocalDate.of(2026, 7, 3),   // Independence Day (observed)
            LocalDate.of(2026, 9, 7),   // Labor Day
            LocalDate.of(2026, 11, 26), // Thanksgiving
            LocalDate.of(2026, 12, 25), // Christmas
            // 2027
            LocalDate.of(2027, 1, 1),   // New Year's Day
            LocalDate.of(2027, 1, 18),  // MLK Day
            LocalDate.of(2027, 2, 15),  // Presidents' Day
            LocalDate.of(2027, 3, 26),  // Good Friday
            LocalDate.of(2027, 5, 31),  // Memorial Day
            LocalDate.of(2027, 6, 18),  // Juneteenth (observed)
            LocalDate.of(2027, 7, 5),   // Independence Day (observed)
            LocalDate.of(2027, 9, 6),   // Labor Day
            LocalDate.of(2027, 11, 25), // Thanksgiving
            LocalDate.of(2027, 12, 24)  // Christmas Eve (early close)
    );

    /**
     * Checks if the given date/time is during US market hours (including extended).
     */
    public boolean isMarketOpen(ZonedDateTime dateTime) {
        ZonedDateTime et = dateTime.withZoneSameInstant(ET);
        DayOfWeek dow = et.getDayOfWeek();

        // Weekends: no trading
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }

        // Market holidays: no trading
        if (MARKET_HOLIDAYS.contains(et.toLocalDate())) {
            return false;
        }

        LocalTime time = et.toLocalTime();
        // Extended hours: 4:00 AM - 8:00 PM ET
        return !time.isBefore(PRE_MARKET_OPEN) && !time.isAfter(AFTER_HOURS_CLOSE);
    }

    /**
     * Checks if the market is currently open (real-time check).
     */
    public boolean isMarketOpenNow() {
        return isMarketOpen(ZonedDateTime.now(ET));
    }

    /**
     * Checks if the given time is during REGULAR market hours only (9:30 AM - 4:00 PM ET).
     * Excludes pre-market (4:00-9:30 AM) and after-hours (4:00-8:00 PM).
     */
    public boolean isRegularMarketHours(ZonedDateTime dateTime) {
        ZonedDateTime et = dateTime.withZoneSameInstant(ET);
        DayOfWeek dow = et.getDayOfWeek();

        // Weekends: no trading
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }

        // Market holidays: no trading
        if (MARKET_HOLIDAYS.contains(et.toLocalDate())) {
            return false;
        }

        LocalTime time = et.toLocalTime();
        // Regular hours only: 9:30 AM - 4:00 PM ET
        return !time.isBefore(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
    }

    /**
     * Determines if we should attempt to download fresh candle data.
     * 
     * Logic:
     * - If lastCandle is from a weekend/holiday → don't download until next market open
     * - If lastCandle is from before pre-market today → only download if market is open now
     * - If lastCandle is from after after-hours → don't download until next market open
     * - Otherwise, check freshness threshold
     * 
     * @param lastCandleTime timestamp of the most recent candle we have
     * @param timeframeMinutes the timeframe in minutes (5, 15, 60, 1440)
     * @param freshnessThresholdMinutes how old the data can be before it's considered stale
     * @return true if we should download fresh data
     */
    public boolean shouldDownloadData(ZonedDateTime lastCandleTime, int timeframeMinutes, int freshnessThresholdMinutes) {
        ZonedDateTime now = ZonedDateTime.now(ET);
        ZonedDateTime lastCandle = lastCandleTime.withZoneSameInstant(ET);

        // If the last candle is very recent (within threshold), no need to download
        Duration age = Duration.between(lastCandle, now);
        if (age.toMinutes() < freshnessThresholdMinutes) {
            log.trace("Data is fresh enough ({} min old < {} min threshold), skipping download",
                    age.toMinutes(), freshnessThresholdMinutes);
            return false;
        }

        // If last candle was on a weekend → wait until Monday pre-market
        DayOfWeek lastDow = lastCandle.getDayOfWeek();
        if (lastDow == DayOfWeek.SATURDAY || lastDow == DayOfWeek.SUNDAY) {
            LocalDate nextMonday = lastCandle.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            ZonedDateTime nextMarketOpen = nextMonday.atTime(PRE_MARKET_OPEN).atZone(ET);
            if (now.isBefore(nextMarketOpen)) {
                log.debug("Last candle from weekend ({}), next market open is {}, skipping download",
                        lastDow, nextMarketOpen);
                return false;
            }
        }

        // If last candle was on a holiday → wait until next trading day
        if (MARKET_HOLIDAYS.contains(lastCandle.toLocalDate())) {
            LocalDate nextTradingDay = findNextTradingDay(lastCandle.toLocalDate());
            if (nextTradingDay != null && now.toLocalDate().isBefore(nextTradingDay)) {
                log.debug("Last candle from holiday {}, next trading day is {}, skipping download",
                        lastCandle.toLocalDate(), nextTradingDay);
                return false;
            }
        }

        // If last candle was from a previous day and market is not open now → skip
        LocalDate lastDate = lastCandle.toLocalDate();
        LocalDate today = now.toLocalDate();

        if (lastDate.isBefore(today)) {
            if (!isMarketOpenNow()) {
                log.debug("Last candle from {} and market is closed now ({} ET), skipping download",
                        lastDate, now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                return false;
            }
            // Market is open now, we might have new data → download
            return true;
        }

        // Last candle is from today
        LocalTime lastTime = lastCandle.toLocalTime();
        LocalTime currentTime = now.toLocalTime();

        // If last candle was before pre-market and we're still before pre-market → skip
        if (lastTime.isBefore(PRE_MARKET_OPEN) && currentTime.isBefore(PRE_MARKET_OPEN)) {
            log.debug("Last candle from {} before pre-market, still before pre-market, skipping download", lastTime);
            return false;
        }

        // If last candle was after after-hours and we're still after after-hours → skip
        if (lastTime.isAfter(AFTER_HOURS_CLOSE) && currentTime.isAfter(AFTER_HOURS_CLOSE)) {
            log.debug("Last candle from {} after after-hours, still after after-hours, skipping download", lastTime);
            return false;
        }

        // Default: data is old enough and we might be in trading hours → download
        return true;
    }

    /**
     * Finds the next trading day after the given date.
     * Returns null if no trading day is found within 14 days.
     */
    private LocalDate findNextTradingDay(LocalDate fromDate) {
        LocalDate candidate = fromDate.plusDays(1);
        for (int i = 0; i < 14; i++) {
            DayOfWeek dow = candidate.getDayOfWeek();
            boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            boolean isHoliday = MARKET_HOLIDAYS.contains(candidate);

            if (!isWeekend && !isHoliday) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return null;
    }

    /**
     * Returns the next market open time from now.
     */
    public ZonedDateTime getNextMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(ET);
        LocalDate today = now.toLocalDate();

        // Check if today is a trading day and we're before market open
        if (!MARKET_HOLIDAYS.contains(today) &&
                now.getDayOfWeek().getValue() < 6 &&
                now.toLocalTime().isBefore(PRE_MARKET_OPEN)) {
            return today.atTime(PRE_MARKET_OPEN).atZone(ET);
        }

        // Otherwise find next trading day
        LocalDate nextTrading = findNextTradingDay(today);
        if (nextTrading != null) {
            return nextTrading.atTime(PRE_MARKET_OPEN).atZone(ET);
        }

        // Fallback: just return tomorrow at pre-market
        return today.plusDays(1).atTime(PRE_MARKET_OPEN).atZone(ET);
    }

    /**
     * Returns the next REGULAR market open time from now (9:30 AM ET).
     * This is used for UI countdown display when users want to know when
     * regular trading hours begin (not pre-market).
     */
    public ZonedDateTime getNextRegularMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(ET);
        LocalDate today = now.toLocalDate();

        // Check if today is a regular trading day
        boolean isTradingDay = !MARKET_HOLIDAYS.contains(today) &&
                now.getDayOfWeek().getValue() < 6;

        if (isTradingDay) {
            // If we're before regular market open today, return today's regular open
            if (now.toLocalTime().isBefore(MARKET_OPEN)) {
                return today.atTime(MARKET_OPEN).atZone(ET);
            }
            // If we're during regular hours or after, return next trading day
            LocalDate nextTrading = findNextTradingDay(today);
            if (nextTrading != null) {
                return nextTrading.atTime(MARKET_OPEN).atZone(ET);
            }
        } else {
            // Today is holiday or weekend, find next trading day
            LocalDate nextTrading = findNextTradingDay(today);
            if (nextTrading != null) {
                return nextTrading.atTime(MARKET_OPEN).atZone(ET);
            }
        }

        // Fallback: just return tomorrow at regular market open
        return today.plusDays(1).atTime(MARKET_OPEN).atZone(ET);
    }

    /**
     * Checks if a given date is a US market holiday.
     */
    public boolean isMarketHoliday(LocalDate date) {
        return MARKET_HOLIDAYS.contains(date);
    }

    /**
     * Gets the current time in ET for debugging/logging.
     */
    public ZonedDateTime nowET() {
        return ZonedDateTime.now(ET);
    }
}
