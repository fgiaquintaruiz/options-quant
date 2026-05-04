package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.candle.RepositoryException;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Migrates historical candle data from CSV files to SQLite.
 *
 * <p>Executed on startup before any other trading or backtest logic.
 * Scans the {@code data/} directory for {@code .csv} files, parses them,
 * and upserts them into the {@code candles} table.
 *
 * <p>Idempotent: records the migration status in {@code schema_meta} to skip
 * subsequent runs.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class CandleStoreMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CandleStoreMigrator.class);

    private final CandleCsvService csvService;
    private final CandleRepository repository;
    private final JdbcTemplate jdbc;

    @Value("${candles.data-path:data}")
    private String dataPath = "data";

    @Value("${candles.archive-csv:false}")
    private boolean archiveCsv = false;

    public CandleStoreMigrator(CandleCsvService csvService,
                                CandleRepository repository,
                                @Qualifier("candlesWriteDs") DataSource ds) {
        this.csvService = csvService;
        this.repository = repository;
        this.jdbc = new JdbcTemplate(ds);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isMigrationNeeded()) {
            log.info("CSV to SQLite migration already completed. Skipping.");
            return;
        }

        log.info("Starting CSV to SQLite migration from path: {}", dataPath);
        Path root = Path.of(dataPath);
        if (!Files.exists(root)) {
            log.warn("Data path {} does not exist. Nothing to migrate.", dataPath);
            markMigrationComplete();
            return;
        }

        File[] files = root.toFile().listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            log.info("No CSV files found in {}.", dataPath);
            markMigrationComplete();
            return;
        }

        Path archiveDir = null;
        if (archiveCsv) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            archiveDir = root.resolve("_archived_csv_" + timestamp);
            Files.createDirectories(archiveDir);
            log.info("Archiving migrated CSVs to: {}", archiveDir);
        }

        int migratedCount = 0;
        for (File file : files) {
            try {
                ParsedFileName parsed = parseCsvName(file.getName());
                log.info("Migrating {} ({} {})...", file.getName(), parsed.ticker(), parsed.timeframe());

                List<Candle> candles = csvService.loadFromCsv(parsed.ticker(), parsed.timeframe());
                if (!candles.isEmpty()) {
                    repository.upsert(parsed.ticker(), parsed.timeframe(), candles);
                }

                if (archiveCsv && archiveDir != null) {
                    Files.move(file.toPath(), archiveDir.resolve(file.getName()));
                }
                migratedCount++;
            } catch (Exception e) {
                log.error("Failed to migrate file {}: {}", file.getName(), e.getMessage());
                // Continue with other files
            }
        }

        markMigrationComplete();
        log.info("CSV to SQLite migration finished. Migrated {} files.", migratedCount);
    }

    public boolean isMigrationNeeded() {
        String sql = "SELECT value FROM schema_meta WHERE key = 'csv_migration_status'";
        List<String> results = jdbc.queryForList(sql, String.class);
        return results.isEmpty() || !"COMPLETE".equals(results.get(0));
    }

    private void markMigrationComplete() {
        jdbc.update("INSERT OR REPLACE INTO schema_meta (key, value) VALUES (?, ?)",
                "csv_migration_status", "COMPLETE");
    }

    /**
     * Parses a filename like "AAPL_5min.csv" into ticker and TimeFrame.
     */
    public ParsedFileName parseCsvName(String filename) {
        String nameWithoutExt = filename.substring(0, filename.lastIndexOf('.'));
        int lastUnderscore = nameWithoutExt.lastIndexOf('_');

        if (lastUnderscore == -1) {
            throw new RepositoryException("Invalid CSV filename format (expected TICKER_timeframe.csv): " + filename);
        }

        String ticker = nameWithoutExt.substring(0, lastUnderscore);
        String tfSuffix = nameWithoutExt.substring(lastUnderscore + 1);

        TimeFrame tf = switch (tfSuffix.toLowerCase()) {
            case "5min" -> TimeFrame.MIN_5;
            case "15min" -> TimeFrame.MIN_15;
            case "1hour" -> TimeFrame.HOUR_1;
            case "1day" -> TimeFrame.DAY_1;
            default -> throw new RepositoryException("Unsupported timeframe suffix in filename: " + tfSuffix);
        };

        return new ParsedFileName(ticker, tf);
    }

    public void setDataPath(String dataPath) {
        this.dataPath = dataPath;
    }

    public void setArchiveCsv(boolean archiveCsv) {
        this.archiveCsv = archiveCsv;
    }

    public record ParsedFileName(String ticker, TimeFrame timeframe) {}
}
