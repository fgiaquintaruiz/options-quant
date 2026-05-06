package com.fgiaquinta.optionsquant.candle.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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

    public static class PeriodEntry {
        private String from;
        private String to;

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }
}
