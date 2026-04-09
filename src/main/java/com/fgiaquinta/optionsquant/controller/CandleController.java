package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import com.fgiaquinta.optionsquant.service.CandleDownloadService;
import com.fgiaquinta.optionsquant.service.IbkrService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for candle operations.
 * Thin layer: delegates to services, returns typed responses.
 */
@Slf4j
@RestController
@RequestMapping("/api/candles")
@RequiredArgsConstructor
public class CandleController {

    private final CandleDownloadService downloadService;
    private final CandleCsvService csvService;
    private final IbkrService ibkrService;
    private final MetricsService metrics;

    @Timed(value = "candles.download", description = "Download historical data for a ticker/timeframe")
    @PostMapping("/download")
    public ResponseEntity<CandleApiResponses.DownloadResponse> download(
            @RequestParam String ticker,
            @RequestParam TimeFrame timeframe,
            @RequestParam(defaultValue = "true") boolean saveToCsv
    ) {
        log.info(">>> POST /api/candles/download ticker={} timeframe={} saveToCsv={}", ticker, timeframe, saveToCsv);
        metrics.incrementHttpCall("/api/candles/download", "POST");

        CandleApiResponses.DownloadResponse response = downloadService.downloadAndSave(ticker, timeframe, saveToCsv);

        log.info("<<< POST /api/candles/download - {} candles", response.candles());
        return ResponseEntity.ok(response);
    }

    @Timed(value = "candles.downloadAll", description = "Download all timeframes for a ticker")
    @PostMapping("/download-all")
    public ResponseEntity<CandleApiResponses.DownloadAllResponse> downloadAll(
            @RequestParam String ticker,
            @RequestParam(defaultValue = "true") boolean saveToCsv
    ) {
        log.info(">>> POST /api/candles/download-all ticker={} saveToCsv={}", ticker, saveToCsv);
        metrics.incrementHttpCall("/api/candles/download-all", "POST");

        CandleApiResponses.DownloadAllResponse response = downloadService.downloadAllTimeframesAndSave(ticker, saveToCsv);

        log.info("<<< POST /api/candles/download-all - {} timeframes", response.timeframes().size());
        return ResponseEntity.ok(response);
    }

    @Timed(value = "candles.downloadAllTickers", description = "Download all timeframes for all configured tickers")
    @PostMapping("/download-all-tickers")
    public ResponseEntity<CandleApiResponses.DownloadAllTickersResponse> downloadAllTickers(
            @RequestParam(defaultValue = "true") boolean saveToCsv
    ) {
        log.info(">>> POST /api/candles/download-all-tickers saveToCsv={}", saveToCsv);
        metrics.incrementHttpCall("/api/candles/download-all-tickers", "POST");

        CandleApiResponses.DownloadAllTickersResponse response = downloadService.downloadAllTickersAndSave(saveToCsv);

        log.info("<<< POST /api/candles/download-all-tickers - {}/{} tickers successful",
                response.successfulTickers(), response.totalTickers());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/local")
    public ResponseEntity<List<Candle>> loadLocal(
            @RequestParam String ticker,
            @RequestParam TimeFrame timeframe
    ) {
        log.debug(">>> GET /api/candles/local ticker={} timeframe={}", ticker, timeframe);
        metrics.incrementHttpCall("/api/candles/local", "GET");

        List<Candle> candles = csvService.loadFromCsv(ticker, timeframe);

        log.debug("<<< GET /api/candles/local - {} candles", candles.size());
        return ResponseEntity.ok(candles);
    }

    @GetMapping("/local/exists")
    public ResponseEntity<Boolean> hasLocalData(
            @RequestParam String ticker,
            @RequestParam TimeFrame timeframe
    ) {
        log.debug(">>> GET /api/candles/local/exists ticker={} timeframe={}", ticker, timeframe);

        boolean exists = csvService.hasLocalData(ticker, timeframe);
        return ResponseEntity.ok(exists);
    }

    @GetMapping("/status")
    public ResponseEntity<CandleApiResponses.StatusResponse> status() {
        log.debug(">>> GET /api/candles/status");
        boolean connected = ibkrService.isConnected();
        log.debug("<<< GET /api/candles/status - connected={}", connected);
        return ResponseEntity.ok(new CandleApiResponses.StatusResponse(connected));
    }

    @PostMapping("/connect")
    public ResponseEntity<CandleApiResponses.StatusResponse> connect() {
        log.info(">>> POST /api/candles/connect - Connecting to IBKR");
        metrics.incrementHttpCall("/api/candles/connect", "POST");
        
        ibkrService.connect();
        
        boolean connected = ibkrService.isConnected();
        log.info("<<< POST /api/candles/connect - connected={}", connected);
        return ResponseEntity.ok(new CandleApiResponses.StatusResponse(connected));
    }
}
