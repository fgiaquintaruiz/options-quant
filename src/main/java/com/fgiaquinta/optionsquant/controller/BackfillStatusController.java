package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.candle.backfill.BackfillProgressTracker;
import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatusSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for backfill progress visibility (R2.2).
 *
 * <p>GET /api/v1/backfill/status → returns a {@link BackfillStatusSnapshot} as JSON.
 * When no backfill is running the snapshot contains timeframe="IDLE" and all-zero metrics.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/backfill")
@RequiredArgsConstructor
public class BackfillStatusController {

    private final BackfillProgressTracker tracker;

    @GetMapping("/status")
    public ResponseEntity<BackfillStatusSnapshot> getStatus() {
        log.debug(">>> GET /api/v1/backfill/status");
        BackfillStatusSnapshot status = tracker.getStatus();
        log.debug("<<< GET /api/v1/backfill/status — timeframe={} {}/{} running={}",
                status.timeframe(), status.completedTickers(), status.totalTickers(), status.running());
        return ResponseEntity.ok(status);
    }
}
