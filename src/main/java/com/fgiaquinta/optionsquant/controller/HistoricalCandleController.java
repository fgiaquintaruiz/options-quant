package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.controller.dto.CandleResponse;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
}
