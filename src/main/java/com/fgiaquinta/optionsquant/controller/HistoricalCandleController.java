package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.controller.dto.CandleResponse;
import com.fgiaquinta.optionsquant.controller.dto.CandleWithFundamentalsResponse;
import com.fgiaquinta.optionsquant.controller.dto.TickerInfoDto;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Exposes historical candles stored in SQLite via a clean REST contract.
 * The Python sidecar used this URL internally; this is the Java-native equivalent.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/historical")
@RequiredArgsConstructor
public class HistoricalCandleController {

    private static final Map<String, TimeFrame> INTERVAL_MAP = Map.of(
            "1d",  TimeFrame.DAY_1,
            "1h",  TimeFrame.HOUR_1,
            "15m", TimeFrame.MIN_15,
            "5m",  TimeFrame.MIN_5
    );

    private final CandleRepository candleRepository;
    private final TickerService tickerService;

    @GetMapping("/{ticker}")
    public ResponseEntity<?> getCandles(
            @PathVariable String ticker,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam String interval
    ) {
        log.info(">>> GET /api/v1/historical/{} from={} to={} interval={}", ticker, from, to, interval);

        TimeFrame tf = INTERVAL_MAP.get(interval);
        if (tf == null) {
            return ResponseEntity.badRequest()
                    .body(new CandleApiResponses.ErrorResponse("Unknown interval. Use: 1d, 1h, 15m, 5m"));
        }

        if (from.isAfter(to)) {
            return ResponseEntity.badRequest()
                    .body(new CandleApiResponses.ErrorResponse("'from' must not be after 'to'"));
        }

        ZonedDateTime fromZ = from.atStartOfDay(ZoneOffset.UTC);
        // +1 day makes 'to' inclusive at day boundary, matching user expectations for date ranges
        ZonedDateTime toZ   = to.plusDays(1).atStartOfDay(ZoneOffset.UTC);

        List<Candle> candles = candleRepository.loadRange(ticker.toUpperCase(), tf, fromZ, toZ);

        List<CandleResponse> response = candles.stream()
                .map(c -> new CandleResponse(
                        ticker.toUpperCase(),
                        c.timestamp().toLocalDate().toString(),
                        c.open(),
                        c.high(),
                        c.low(),
                        c.close(),
                        c.volume()))
                .toList();

        log.info("<<< GET /api/v1/historical/{} - {} candles", ticker, response.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{ticker}/with-fundamentals")
    public ResponseEntity<?> getCandlesWithFundamentals(
            @PathVariable String ticker,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "1d") String interval) {

        String upper = ticker.toUpperCase();
        log.info(">>> GET /api/v1/historical/{}/with-fundamentals from={} to={} interval={}", upper, from, to, interval);

        TickerInfo info = tickerService.getTickerInfo(upper).orElse(null);
        if (info == null) {
            log.info("<<< GET /api/v1/historical/{}/with-fundamentals - ticker not found", upper);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "ticker not found", "ticker", upper));
        }

        TimeFrame tf = INTERVAL_MAP.get(interval);
        if (tf == null) {
            return ResponseEntity.badRequest()
                    .body(new CandleApiResponses.ErrorResponse("Unknown interval. Use: 1d, 1h, 15m, 5m"));
        }

        if (from.isAfter(to)) {
            return ResponseEntity.badRequest()
                    .body(new CandleApiResponses.ErrorResponse("'from' must not be after 'to'"));
        }

        ZonedDateTime fromZ = from.atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime toZ   = to.plusDays(1).atStartOfDay(ZoneOffset.UTC);

        List<CandleResponse> candles = candleRepository.loadRange(upper, tf, fromZ, toZ)
                .stream()
                .map(c -> new CandleResponse(
                        upper,
                        c.timestamp().toLocalDate().toString(),
                        c.open(),
                        c.high(),
                        c.low(),
                        c.close(),
                        c.volume()))
                .toList();

        log.info("<<< GET /api/v1/historical/{}/with-fundamentals - {} candles", upper, candles.size());
        return ResponseEntity.ok(new CandleWithFundamentalsResponse(TickerInfoDto.from(info), candles));
    }
}
