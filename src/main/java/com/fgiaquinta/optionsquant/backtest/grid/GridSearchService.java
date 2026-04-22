package com.fgiaquinta.optionsquant.backtest.grid;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.config.GridSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Grid over discrete axis values; runs {@link BacktestEngine} once per selected cell (serial v1).
 * Supports exhaustive Cartesian product or random sampling without replacement from that product.
 * Optional rolling walk-forward: grid on each in-sample window, then one OOS backtest using the best IS cell.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridSearchService {

    private static final Set<String> V1_AXIS_NAMES = Set.of("tpMultiplierDelta", "slMultiplierDelta");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BacktestEngine backtestEngine;
    private final GridSearchProperties properties;

    private record IndexedCell(int cellIndex, Map<String, Double> params) {}

    /**
     * Runs selected grid cells; writes CSV under {@code backtest/grid-results/}.
     * When walk-forward fields are set, runs rolling IS grid + OOS validation instead of a single-range grid.
     */
    public GridSearchResult run(GridSearchRequest request) {
        validateRequest(request);
        if (isWalkForwardEnabled(request)) {
            validateWalkForward(request);
            return runWalkForward(request);
        }
        List<IndexedCell> plan = buildPlan(request);
        long deadlineMs = computeDeadline();
        Path csvPath = Path.of("backtest/grid-results", "grid-search-" + LocalDateTime.now().format(FILE_TS) + ".csv");
        return executePlannedGrid(request, plan, deadlineMs, csvPath, true);
    }

    private boolean isWalkForwardEnabled(GridSearchRequest request) {
        Integer tr = request.walkForwardTrainDays();
        Integer te = request.walkForwardTestDays();
        if (tr == null && te == null) {
            return false;
        }
        if (tr == null || te == null) {
            throw new IllegalArgumentException(
                    "walkForwardTrainDays and walkForwardTestDays must both be set (or both omitted) for walk-forward");
        }
        if (tr < 1 || te < 1) {
            throw new IllegalArgumentException("walk-forward train and test window lengths must be >= 1 day");
        }
        return true;
    }

    private void validateWalkForward(GridSearchRequest request) {
        String mode = request.searchMode() == null || request.searchMode().isBlank()
                ? "EXHAUSTIVE"
                : request.searchMode().trim().toUpperCase(Locale.ROOT);
        if ("RANDOM".equals(mode)) {
            throw new IllegalArgumentException("walk-forward is not supported with RANDOM searchMode in v1");
        }
        int trainD = request.walkForwardTrainDays();
        int testD = request.walkForwardTestDays();
        LocalDate firstTestTo = request.fromDate().plusDays((long) trainD + testD - 2L);
        if (firstTestTo.isAfter(request.toDate())) {
            throw new IllegalArgumentException(
                    ("Date range too short for walk-forward: [fromDate, toDate] must span at least "
                            + "trainDays + testDays - 1 calendar days (got train=%d test=%d).")
                            .formatted(trainD, testD));
        }
        if (request.walkForwardStepDays() != null && request.walkForwardStepDays() < 1) {
            throw new IllegalArgumentException("walkForwardStepDays must be >= 1 when set");
        }
    }

    private int countWalkForwardFolds(GridSearchRequest request, int trainD, int testD, int step) {
        int n = 0;
        LocalDate rangeEnd = request.toDate();
        for (LocalDate trainFrom = request.fromDate(); ; trainFrom = trainFrom.plusDays(step)) {
            LocalDate trainTo = trainFrom.plusDays((long) trainD - 1L);
            LocalDate testFrom = trainTo.plusDays(1L);
            LocalDate testTo = testFrom.plusDays((long) testD - 1L);
            if (testTo.isAfter(rangeEnd)) {
                break;
            }
            n++;
        }
        return n;
    }

    private GridSearchRequest copyRequestWithDates(GridSearchRequest base, LocalDate from, LocalDate to) {
        return new GridSearchRequest(
                base.tickers(),
                from,
                to,
                base.initialCapital(),
                base.riskPerTradePct(),
                base.slippagePct(),
                base.commissionPerContract(),
                base.maxConcurrentTrades(),
                base.executionTimeframe(),
                base.includeTradePlans(),
                base.deterministicMode(),
                base.axes(),
                base.searchMode(),
                base.randomSampleCount(),
                base.randomSeed(),
                base.constraintMinTrades(),
                base.constraintMaxDrawdownPct(),
                base.primaryMetric(),
                null,
                null,
                null
        );
    }

    private GridSearchResult runWalkForward(GridSearchRequest request) {
        int trainD = request.walkForwardTrainDays();
        int testD = request.walkForwardTestDays();
        int step = (request.walkForwardStepDays() != null && request.walkForwardStepDays() >= 1)
                ? request.walkForwardStepDays()
                : testD;

        List<IndexedCell> planTemplate = buildPlan(request);
        int cellsPerFold = planTemplate.size();
        int foldsPlanned = countWalkForwardFolds(request, trainD, testD, step);
        int requestedTotal = foldsPlanned * (cellsPerFold + 1);
        int completedTotal = 0;

        long deadlineMs = computeDeadline();
        List<WalkForwardFoldResult> folds = new ArrayList<>();
        String ts = LocalDateTime.now().format(FILE_TS);
        Path summaryCsv = Path.of("backtest/grid-results", "walk-forward-" + ts + ".csv");

        double aggregateOosPnl = 0.0;
        int aggregateOosTrades = 0;
        int foldsWithOos = 0;
        int foldIndex = 0;

        try {
            Files.createDirectories(Path.of("backtest/grid-results"));
            for (LocalDate trainFrom = request.fromDate(); ; trainFrom = trainFrom.plusDays(step)) {
                LocalDate trainTo = trainFrom.plusDays((long) trainD - 1L);
                LocalDate testFrom = trainTo.plusDays(1L);
                LocalDate testTo = testFrom.plusDays((long) testD - 1L);
                if (testTo.isAfter(request.toDate())) {
                    break;
                }
                final int thisFold = foldIndex;
                if (System.currentTimeMillis() > deadlineMs) {
                    folds.add(timeoutFold(thisFold, trainFrom, trainTo, testFrom, testTo, "global deadline before fold"));
                    writeWalkForwardCsv(summaryCsv, folds);
                    WalkForwardOosSummary sum = buildWfSummary(
                            request.primaryMetric(), foldsPlanned, folds.size(), foldsWithOos, aggregateOosPnl, aggregateOosTrades,
                            "Stopped early (grid-search.timeout-ms).");
                    return new GridSearchResult(false,
                            "Walk-forward stopped by timeout after %d folds.".formatted(folds.size()),
                            requestedTotal, completedTotal, List.of(), summaryCsv.toString().replace('\\', '/'),
                            null, folds, sum);
                }

                GridSearchRequest isReq = copyRequestWithDates(request, trainFrom, trainTo);
                Path isCsv = Path.of("backtest/grid-results", "walk-forward-" + ts + "-fold" + foldIndex + "-is.csv");
                GridSearchResult isGrid = executePlannedGrid(isReq, planTemplate, deadlineMs, isCsv, true);

                completedTotal += isGrid.completedCells();
                boolean incompleteIs = isGrid.completedCells() < isGrid.requestedCells();
                GridOptimizationSummary opt = isGrid.optimization();
                boolean hasWinner = opt != null && opt.hasWinner();
                Map<String, Double> bestParams = hasWinner && opt.bestParameters() != null
                        ? Map.copyOf(opt.bestParameters())
                        : Map.of();
                Double bestMetric = hasWinner ? opt.bestMetricValue() : null;

                if (incompleteIs) {
                    folds.add(new WalkForwardFoldResult(
                            thisFold, trainFrom, trainTo, testFrom, testTo,
                            false, Map.of(), null, isGrid.message(), isGrid.completedCells(), isGrid.requestedCells(),
                            0.0, 0, 0.0, 0.0, 0.0, 0L,
                            "in-sample grid incomplete (timeout or error); OOS skipped"));
                    foldIndex++;
                    writeWalkForwardCsv(summaryCsv, folds);
                    WalkForwardOosSummary sum = buildWfSummary(
                            request.primaryMetric(), foldsPlanned, folds.size(), foldsWithOos, aggregateOosPnl, aggregateOosTrades,
                            "Partial walk-forward: IS grid did not finish for fold " + thisFold + ".");
                    return new GridSearchResult(false, isGrid.message(), requestedTotal, completedTotal, List.of(),
                            summaryCsv.toString().replace('\\', '/'), null, folds, sum);
                }

                if (!hasWinner) {
                    String skip = opt != null ? opt.detailMessage() : "no optimization summary";
                    folds.add(new WalkForwardFoldResult(
                            thisFold, trainFrom, trainTo, testFrom, testTo,
                            false, Map.of(), null, isGrid.message(), isGrid.completedCells(), isGrid.requestedCells(),
                            0.0, 0, 0.0, 0.0, 0.0, 0L,
                            "no admissible in-sample winner (" + skip + ")"));
                    foldIndex++;
                    continue;
                }

                double tpDelta = bestParams.getOrDefault("tpMultiplierDelta", 0.0);
                double slDelta = bestParams.getOrDefault("slMultiplierDelta", 0.0);
                if (System.currentTimeMillis() > deadlineMs) {
                    folds.add(new WalkForwardFoldResult(
                            thisFold, trainFrom, trainTo, testFrom, testTo,
                            true, bestParams, bestMetric, isGrid.message(), isGrid.completedCells(), isGrid.requestedCells(),
                            0.0, 0, 0.0, 0.0, 0.0, 0L,
                            "deadline before OOS run (grid-search.timeout-ms)"));
                    foldIndex++;
                    writeWalkForwardCsv(summaryCsv, folds);
                    WalkForwardOosSummary sum = buildWfSummary(
                            request.primaryMetric(), foldsPlanned, folds.size(), foldsWithOos, aggregateOosPnl, aggregateOosTrades,
                            "Timeout before OOS.");
                    return new GridSearchResult(false, "Walk-forward timeout before OOS on fold " + thisFold,
                            requestedTotal, completedTotal, List.of(), summaryCsv.toString().replace('\\', '/'),
                            null, folds, sum);
                }

                BacktestConfig oosCfg = new BacktestConfig(
                        request.tickers(),
                        testFrom,
                        testTo,
                        request.initialCapital(),
                        request.riskPerTradePct(),
                        request.slippagePct(),
                        request.commissionPerContract(),
                        request.maxConcurrentTrades(),
                        request.executionTimeframe(),
                        request.includeTradePlans(),
                        request.deterministicMode(),
                        tpDelta,
                        slDelta
                );
                long t0 = System.currentTimeMillis();
                BacktestReport oosRep = backtestEngine.run(oosCfg, false, null);
                long oosMs = System.currentTimeMillis() - t0;
                completedTotal += 1;

                aggregateOosPnl += oosRep.totalReturn();
                aggregateOosTrades += oosRep.totalTrades();
                foldsWithOos++;

                folds.add(new WalkForwardFoldResult(
                        thisFold, trainFrom, trainTo, testFrom, testTo,
                        true, bestParams, bestMetric, isGrid.message(), isGrid.completedCells(), isGrid.requestedCells(),
                        oosRep.totalReturn(),
                        oosRep.totalTrades(),
                        oosRep.winRate(),
                        oosRep.maxDrawdownPct(),
                        oosRep.profitFactor(),
                        oosMs,
                        null));
                foldIndex++;
            }

            writeWalkForwardCsv(summaryCsv, folds);
            String metric = request.primaryMetric() == null || request.primaryMetric().isBlank()
                    ? "TOTAL_PNL"
                    : request.primaryMetric().trim().toUpperCase(Locale.ROOT);
            String detail = "aggregate OOS PnL=%.2f over %d folds with OOS runs (%d/%d planned folds completed)"
                    .formatted(aggregateOosPnl, foldsWithOos, folds.size(), foldsPlanned);
            WalkForwardOosSummary sum = new WalkForwardOosSummary(
                    foldsPlanned, folds.size(), foldsWithOos, aggregateOosPnl, aggregateOosTrades, metric, detail);
            log.info("Walk-forward finished: {} folds, {} OOS runs, aggregate OOS PnL={} → {}",
                    folds.size(), foldsWithOos, aggregateOosPnl, summaryCsv);
            return new GridSearchResult(true, "walk-forward ok", requestedTotal, completedTotal, List.of(),
                    summaryCsv.toString().replace('\\', '/'), null, folds, sum);

        } catch (Exception e) {
            log.error("Walk-forward failed: {}", e.getMessage(), e);
            WalkForwardOosSummary sum = buildWfSummary(
                    request.primaryMetric(), foldsPlanned, folds.size(), foldsWithOos, aggregateOosPnl, aggregateOosTrades,
                    e.getMessage());
            return new GridSearchResult(false, e.getMessage(), requestedTotal, completedTotal, List.of(),
                    folds.isEmpty() ? null : summaryCsv.toString().replace('\\', '/'), null, folds, sum);
        }
    }

    private static WalkForwardFoldResult timeoutFold(
            int foldIndex, LocalDate trainFrom, LocalDate trainTo, LocalDate testFrom, LocalDate testTo, String reason) {
        return new WalkForwardFoldResult(
                foldIndex, trainFrom, trainTo, testFrom, testTo,
                false, Map.of(), null, reason, 0, 0,
                0.0, 0, 0.0, 0.0, 0.0, 0L,
                reason);
    }

    private static WalkForwardOosSummary buildWfSummary(
            String primaryMetric,
            int foldsPlanned,
            int foldsCompleted,
            int foldsWithOos,
            double aggregateOosPnl,
            int aggregateOosTrades,
            String detail) {
        String metric = primaryMetric == null || primaryMetric.isBlank()
                ? "TOTAL_PNL"
                : primaryMetric.trim().toUpperCase(Locale.ROOT);
        return new WalkForwardOosSummary(foldsPlanned, foldsCompleted, foldsWithOos, aggregateOosPnl, aggregateOosTrades, metric, detail);
    }

    private long computeDeadline() {
        return properties.getTimeoutMs() > 0
                ? System.currentTimeMillis() + properties.getTimeoutMs()
                : Long.MAX_VALUE;
    }

    private List<IndexedCell> buildPlan(GridSearchRequest request) {
        List<Map<String, Double>> allCells = cartesian(request.axes());
        int fullGridSize = allCells.size();
        int maxCells = Math.max(1, properties.getMaxCells());
        String mode = request.searchMode() == null || request.searchMode().isBlank()
                ? "EXHAUSTIVE"
                : request.searchMode().trim().toUpperCase(Locale.ROOT);

        if ("RANDOM".equals(mode)) {
            return planRandomSample(request, allCells, maxCells);
        }
        if ("EXHAUSTIVE".equals(mode)) {
            if (fullGridSize > maxCells) {
                throw new IllegalArgumentException(
                        "Grid has %d cells; configured maximum is %d (grid-search.max-cells). Reduce axes or raise the cap."
                                .formatted(fullGridSize, maxCells));
            }
            List<IndexedCell> plan = new ArrayList<>();
            for (int i = 0; i < allCells.size(); i++) {
                plan.add(new IndexedCell(i, allCells.get(i)));
            }
            return plan;
        }
        throw new IllegalArgumentException("searchMode must be EXHAUSTIVE or RANDOM, got: " + mode);
    }

    /**
     * Runs a pre-built cell plan for {@code request}'s date range.
     */
    private GridSearchResult executePlannedGrid(
            GridSearchRequest request,
            List<IndexedCell> plan,
            long deadlineMs,
            Path csvPath,
            boolean writeFinalCsv) {
        int planned = plan.size();
        List<GridCellResult> rows = new ArrayList<>();
        long gridStart = System.currentTimeMillis();

        try {
            Files.createDirectories(csvPath.getParent());

            for (int run = 0; run < plan.size(); run++) {
                if (System.currentTimeMillis() > deadlineMs) {
                    String msg = "Stopped after %d/%d cells (grid-search.timeout-ms exceeded)."
                            .formatted(rows.size(), planned);
                    log.warn(msg);
                    writeCsv(csvPath, request.axes(), rows);
                    GridOptimizationSummary opt = GridOptimizationSummary.summarize(rows,
                            request.constraintMinTrades(), request.constraintMaxDrawdownPct(), request.primaryMetric());
                    return new GridSearchResult(false, msg, planned, rows.size(), rows,
                            csvPath.toString().replace('\\', '/'), opt, null, null);
                }

                IndexedCell ic = plan.get(run);
                Map<String, Double> params = ic.params();
                double tp = params.getOrDefault("tpMultiplierDelta", 0.0);
                double sl = params.getOrDefault("slMultiplierDelta", 0.0);

                BacktestConfig config = new BacktestConfig(
                        request.tickers(),
                        request.fromDate(),
                        request.toDate(),
                        request.initialCapital(),
                        request.riskPerTradePct(),
                        request.slippagePct(),
                        request.commissionPerContract(),
                        request.maxConcurrentTrades(),
                        request.executionTimeframe(),
                        request.includeTradePlans(),
                        request.deterministicMode(),
                        tp,
                        sl
                );

                long t0 = System.currentTimeMillis();
                BacktestReport report = backtestEngine.run(config, false, null);
                long elapsed = System.currentTimeMillis() - t0;

                rows.add(new GridCellResult(
                        ic.cellIndex(),
                        Map.copyOf(params),
                        report.totalReturn(),
                        report.totalReturnPct(),
                        report.totalTrades(),
                        report.winRate(),
                        report.maxDrawdownPct(),
                        report.profitFactor(),
                        elapsed
                ));
            }

            if (writeFinalCsv) {
                writeCsv(csvPath, request.axes(), rows);
            }
            long wallMs = System.currentTimeMillis() - gridStart;
            log.info("Grid search finished: {} cells in {} ms → {}", planned, wallMs, csvPath);
            GridOptimizationSummary opt = GridOptimizationSummary.summarize(rows,
                    request.constraintMinTrades(), request.constraintMaxDrawdownPct(), request.primaryMetric());
            return new GridSearchResult(true, "ok", planned, rows.size(), rows,
                    csvPath.toString().replace('\\', '/'), opt, null, null);

        } catch (Exception e) {
            log.error("Grid search failed: {}", e.getMessage(), e);
            GridOptimizationSummary opt = GridOptimizationSummary.summarize(rows,
                    request.constraintMinTrades(), request.constraintMaxDrawdownPct(), request.primaryMetric());
            return new GridSearchResult(false, e.getMessage(), planned, rows.size(), rows,
                    csvPath.toString().replace('\\', '/'), opt, null, null);
        }
    }

    private List<IndexedCell> planRandomSample(GridSearchRequest request, List<Map<String, Double>> allCells, int maxCells) {
        Integer sampleCount = request.randomSampleCount();
        if (sampleCount == null || sampleCount < 1) {
            throw new IllegalArgumentException("randomSampleCount is required and must be >= 1 for RANDOM searchMode");
        }
        if (sampleCount > maxCells) {
            throw new IllegalArgumentException(
                    "randomSampleCount (%d) exceeds grid-search.max-cells (%d)".formatted(sampleCount, maxCells));
        }
        if (sampleCount > allCells.size()) {
            throw new IllegalArgumentException(
                    "randomSampleCount (%d) exceeds Cartesian product size (%d)".formatted(sampleCount, allCells.size()));
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < allCells.size(); i++) {
            indices.add(i);
        }
        long seed = request.randomSeed() != null ? request.randomSeed() : System.nanoTime();
        Collections.shuffle(indices, new Random(seed));
        List<IndexedCell> plan = new ArrayList<>();
        for (int i = 0; i < sampleCount; i++) {
            int gi = indices.get(i);
            plan.add(new IndexedCell(gi, allCells.get(gi)));
        }
        return plan;
    }

    private static void writeWalkForwardCsv(Path path, List<WalkForwardFoldResult> folds) throws Exception {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            pw.println("foldIndex,trainFrom,trainTo,testFrom,testTo,isWinner,bestTpDelta,bestSlDelta,oosPnl,oosTrades,oosWinRate,oosMaxDdPct,oosProfitFactor,skippedReason");
            for (WalkForwardFoldResult f : folds) {
                double tp = f.bestIsParameters().getOrDefault("tpMultiplierDelta", Double.NaN);
                double sl = f.bestIsParameters().getOrDefault("slMultiplierDelta", Double.NaN);
                pw.printf(Locale.ROOT, "%d,%s,%s,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s%n",
                        f.foldIndex(),
                        f.trainFrom(),
                        f.trainTo(),
                        f.testFrom(),
                        f.testTo(),
                        f.isOptimizationWinner(),
                        f.isOptimizationWinner() && !Double.isNaN(tp) ? Double.toString(tp) : "",
                        f.isOptimizationWinner() && !Double.isNaN(sl) ? Double.toString(sl) : "",
                        Double.toString(f.oosTotalPnl()),
                        f.oosTotalTrades(),
                        Double.toString(f.oosWinRate()),
                        Double.toString(f.oosMaxDrawdownPct()),
                        Double.toString(f.oosProfitFactor()),
                        f.skippedReason() == null ? "" : csvEscape(f.skippedReason()));
            }
        }
    }

    private static void validateRequest(GridSearchRequest request) {
        if (request.tickers() == null || request.tickers().isEmpty()) {
            throw new IllegalArgumentException("tickers must not be empty");
        }
        if (request.axes() == null || request.axes().isEmpty()) {
            throw new IllegalArgumentException("axes must not be empty");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (GridAxis axis : request.axes()) {
            if (axis.name() == null || axis.name().isBlank()) {
                throw new IllegalArgumentException("axis name must not be blank");
            }
            if (!V1_AXIS_NAMES.contains(axis.name())) {
                throw new IllegalArgumentException("unsupported axis name: " + axis.name()
                        + " (v1 allows: " + V1_AXIS_NAMES + ")");
            }
            if (!seen.add(axis.name())) {
                throw new IllegalArgumentException("duplicate axis name: " + axis.name());
            }
            if (axis.values() == null || axis.values().isEmpty()) {
                throw new IllegalArgumentException("axis values must not be empty: " + axis.name());
            }
            for (Double v : axis.values()) {
                if (v == null || v < -10 || v > 10) {
                    throw new IllegalArgumentException("axis value out of range [-10, 10]: " + axis.name() + " = " + v);
                }
            }
        }
    }

    /**
     * Cartesian product: each map is one cell with keys = axis names.
     */
    static List<Map<String, Double>> cartesian(List<GridAxis> axes) {
        List<Map<String, Double>> out = new ArrayList<>();
        cartesianRecursive(axes, 0, new LinkedHashMap<>(), out);
        return out;
    }

    private static void cartesianRecursive(List<GridAxis> axes, int pos, LinkedHashMap<String, Double> current,
            List<Map<String, Double>> out) {
        if (pos == axes.size()) {
            out.add(Map.copyOf(current));
            return;
        }
        GridAxis ax = axes.get(pos);
        for (Double v : ax.values()) {
            current.put(ax.name(), v);
            cartesianRecursive(axes, pos + 1, current, out);
            current.remove(ax.name());
        }
    }

    static int countCells(List<GridAxis> axes) {
        if (axes.isEmpty()) {
            return 0;
        }
        int p = 1;
        for (GridAxis a : axes) {
            p *= a.values().size();
        }
        return p;
    }

    private static void writeCsv(Path path, List<GridAxis> axes, List<GridCellResult> rows) throws Exception {
        List<String> axisNames = axes.stream().map(GridAxis::name).toList();
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            StringBuilder header = new StringBuilder("cellIndex");
            for (String name : axisNames) {
                header.append(',').append(csvEscape(name));
            }
            header.append(",totalPnl,totalReturnPct,totalTrades,winRate,maxDrawdownPct,profitFactor,elapsedMs");
            pw.println(header);

            for (GridCellResult r : rows) {
                StringBuilder line = new StringBuilder();
                line.append(r.cellIndex());
                for (String name : axisNames) {
                    line.append(',');
                    Double v = r.parameters().get(name);
                    line.append(v != null ? Double.toString(v) : "");
                }
                line.append(',').append(r.totalPnl());
                line.append(',').append(r.totalReturnPct());
                line.append(',').append(r.totalTrades());
                line.append(',').append(r.winRate());
                line.append(',').append(r.maxDrawdownPct());
                line.append(',').append(r.profitFactor());
                line.append(',').append(r.elapsedMs());
                pw.println(line);
            }
        }
    }

    private static String csvEscape(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
