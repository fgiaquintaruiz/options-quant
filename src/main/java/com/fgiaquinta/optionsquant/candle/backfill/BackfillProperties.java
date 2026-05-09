package com.fgiaquinta.optionsquant.candle.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@ConfigurationProperties(prefix = "candles.backfill")
public class BackfillProperties {

    private List<PeriodEntry> periods = List.of();

    public List<PeriodEntry> getPeriods() {
        return periods;
    }

    public void setPeriods(List<PeriodEntry> periods) {
        this.periods = periods != null ? periods : List.of();
    }

    /**
     * Returns periods expanded to {@link HistoricalBackfillService.BackfillPeriod} instances:
     * {@code from} maps to the first day of the month, {@code to} maps to the last day of the month.
     */
    public List<HistoricalBackfillService.BackfillPeriod> parsedPeriods() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        return periods.stream()
                .map(e -> {
                    LocalDate from = YearMonth.parse(e.getFrom(), fmt).atDay(1);
                    LocalDate to = YearMonth.parse(e.getTo(), fmt).atEndOfMonth();
                    return new HistoricalBackfillService.BackfillPeriod(from, to);
                })
                .toList();
    }

    /**
     * Computes the dynamic live-tail period that closes the gap between the last
     * configured critical period and "now". This is the fix for the OUT_OF_RANGE bug
     * where chunks past the last hardcoded period were skipped, freezing the
     * checkpoint and preventing the DB from staying current.
     *
     * <p>Returns {@code null} when no periods are configured (the service then
     * falls back to its empty-periods semantics: no trimming).
     */
    public HistoricalBackfillService.BackfillPeriod livetailPeriod() {
        List<HistoricalBackfillService.BackfillPeriod> parsed = parsedPeriods();
        if (parsed.isEmpty()) {
            return null;
        }
        LocalDate maxTo = parsed.stream()
                .map(HistoricalBackfillService.BackfillPeriod::to)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = maxTo.plusDays(1);
        if (from.isAfter(today)) {
            // last critical period already covers today — no live tail needed
            return null;
        }
        return new HistoricalBackfillService.BackfillPeriod(from, today);
    }

    /**
     * Returns the configured periods plus the dynamic live-tail period appended at the end.
     * This is the list the backfill service consumes at startup.
     */
    public List<HistoricalBackfillService.BackfillPeriod> parsedPeriodsWithLiveTail() {
        List<HistoricalBackfillService.BackfillPeriod> base = parsedPeriods();
        HistoricalBackfillService.BackfillPeriod tail = livetailPeriod();
        if (tail == null) {
            return base;
        }
        List<HistoricalBackfillService.BackfillPeriod> combined = new ArrayList<>(base.size() + 1);
        combined.addAll(base);
        combined.add(tail);
        return List.copyOf(combined);
    }

    public static class PeriodEntry {
        private String from;
        private String to;

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }
}
