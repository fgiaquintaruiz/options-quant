package com.fgiaquinta.optionsquant.backtest.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BacktestCsvWriter.
 */
class BacktestCsvWriterTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // T1 — given results, writes CSV with correct header + data rows
    // -------------------------------------------------------------------------

    @Test
    void givenResults_writesCsvWithCorrectColumns() throws Exception {
        Path outputPath = tempDir.resolve("backtest-results-test.csv");
        BacktestCsvWriter writer = new BacktestCsvWriter(outputPath);

        List<BacktestBatchResult> results = List.of(
                new BacktestBatchResult(
                        "AAPL", "c1 squeeze", "MIN_15",
                        3, 2, 1,
                        0.6667,
                        LocalDate.of(2024, 1, 10),
                        LocalDate.of(2025, 3, 20)
                )
        );

        writer.write(results);

        List<String> lines = Files.readAllLines(outputPath);
        assertThat(lines).hasSize(2); // header + 1 data row
        assertThat(lines.get(0)).isEqualTo(
                "ticker,strategy,timeframe,signal_count,long_signals,short_signals,win_rate,first_signal_date,last_signal_date"
        );
        assertThat(lines.get(1)).startsWith("AAPL,c1 squeeze,MIN_15,3,2,1,");
        assertThat(lines.get(1)).contains("2024-01-10");
        assertThat(lines.get(1)).contains("2025-03-20");
    }

    // -------------------------------------------------------------------------
    // T2 — given empty results, writes header only
    // -------------------------------------------------------------------------

    @Test
    void givenEmptyResults_writesHeaderOnly() throws Exception {
        Path outputPath = tempDir.resolve("backtest-results-empty.csv");
        BacktestCsvWriter writer = new BacktestCsvWriter(outputPath);

        writer.write(List.of());

        List<String> lines = Files.readAllLines(outputPath);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("ticker,strategy,timeframe");
    }

    // -------------------------------------------------------------------------
    // T3 — null dates are written as empty string (no NPE)
    // -------------------------------------------------------------------------

    @Test
    void givenNullDates_writesEmptyStringForDates() throws Exception {
        Path outputPath = tempDir.resolve("backtest-results-nulldates.csv");
        BacktestCsvWriter writer = new BacktestCsvWriter(outputPath);

        List<BacktestBatchResult> results = List.of(
                new BacktestBatchResult(
                        "TSLA", "p2 trend", "DAY_1",
                        0, 0, 0, 0.0, null, null
                )
        );

        writer.write(results);

        List<String> lines = Files.readAllLines(outputPath);
        assertThat(lines).hasSize(2);
        // Last two fields (dates) should be empty
        String dataRow = lines.get(1);
        assertThat(dataRow).endsWith(",,");
    }
}
