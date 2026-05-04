package com.fgiaquinta.optionsquant.candle.csv;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * CSV-backed implementation of {@link CandleRepository}.
 *
 * <p>This is a rollback/fallback adapter: it delegates all reads and writes to
 * {@link CandleCsvService} so the system can operate without SQLite when needed.
 *
 * <p>Active when {@code candles.store=csv}. SQLite is the default store.
 */
@Repository
@ConditionalOnProperty(name = "candles.store", havingValue = "csv")
public class CsvCandleRepository implements CandleRepository {

    private final CandleCsvService csvService;

    public CsvCandleRepository(CandleCsvService csvService) {
        this.csvService = csvService;
    }

    /** No-arg constructor for test use — delegates to an internal CandleCsvService instance. */
    public CsvCandleRepository() {
        this.csvService = new CandleCsvService();
    }

    /** Redirects the underlying CSV data directory. Useful in tests with a temp dir. */
    public void setDataDir(java.nio.file.Path dataDir) {
        this.csvService.setDataDir(dataDir);
    }

    @Override
    public List<Candle> load(String ticker, TimeFrame tf) {
        return csvService.loadFromCsv(ticker, tf);
    }

    @Override
    public void upsert(String ticker, TimeFrame tf, List<Candle> candles) {
        csvService.saveToCsv(ticker, tf, candles);
    }

    @Override
    public boolean hasLocalData(String ticker, TimeFrame tf) {
        return csvService.hasLocalData(ticker, tf);
    }

    @Override
    public Optional<ZonedDateTime> lastTimestamp(String ticker, TimeFrame tf) {
        List<Candle> all = load(ticker, tf);
        return all.stream()
                .map(Candle::timestamp)
                .max(Comparator.naturalOrder());
    }

    @Override
    public List<Candle> loadRange(String ticker, TimeFrame tf,
                                  ZonedDateTime from, ZonedDateTime to) {
        return load(ticker, tf).stream()
                .filter(c -> !c.timestamp().isBefore(from) && c.timestamp().isBefore(to))
                .toList();
    }

    @Override
    public Stream<Candle> stream(String ticker, TimeFrame tf) {
        return load(ticker, tf).stream();
    }
}
