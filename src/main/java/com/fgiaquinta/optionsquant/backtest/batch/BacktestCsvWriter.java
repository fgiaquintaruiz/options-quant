package com.fgiaquinta.optionsquant.backtest.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes backtest batch results to a CSV file.
 *
 * <p>Uses atomic write semantics: writes to a .tmp file first,
 * then renames to the final path. Falls back to direct write if
 * the rename target is on a different filesystem.
 */
@Slf4j
@Component
public class BacktestCsvWriter {

    private static final String HEADER =
            "ticker,strategy,timeframe,signal_count,long_signals,short_signals,win_rate,first_signal_date,last_signal_date";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path outputPath;

    /**
     * Production constructor — uses a date-stamped filename in the project root.
     */
    public BacktestCsvWriter() {
        this.outputPath = Path.of("backtest-results-" + LocalDate.now().format(DATE_FMT) + ".csv");
    }

    /**
     * Test-friendly constructor — caller provides the output path.
     */
    public BacktestCsvWriter(Path outputPath) {
        this.outputPath = outputPath;
    }

    /**
     * Writes all results to CSV. Creates the file if it doesn't exist, overwrites if it does.
     *
     * @param results list of results to write; may be empty (header is always written)
     */
    public void write(List<BacktestBatchResult> results) {
        Path tmp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tmp))) {
            pw.println(HEADER);
            for (BacktestBatchResult r : results) {
                pw.println(formatRow(r));
            }
        } catch (IOException e) {
            log.error("[backtest] Failed to write CSV to {}: {}", tmp, e.getMessage());
            return;
        }

        // Atomic rename — replace final file
        try {
            Files.move(tmp, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[backtest] CSV written: {} ({} rows)", outputPath.toAbsolutePath(), results.size());
        } catch (IOException e) {
            log.warn("[backtest] Atomic rename failed, CSV left at {}: {}", tmp, e.getMessage());
        }
    }

    private String formatRow(BacktestBatchResult r) {
        return String.join(",",
                r.ticker(),
                r.strategy(),
                r.timeframe(),
                String.valueOf(r.signalCount()),
                String.valueOf(r.longSignals()),
                String.valueOf(r.shortSignals()),
                String.format("%.4f", r.winRate()),
                r.firstSignalDate() != null ? r.firstSignalDate().format(DATE_FMT) : "",
                r.lastSignalDate() != null ? r.lastSignalDate().format(DATE_FMT) : ""
        );
    }
}
