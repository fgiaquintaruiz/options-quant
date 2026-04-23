package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayCandleSourceTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate REPLAY_DATE = LocalDate.of(2026, 4, 22);

    private CandleCsvService csvService;
    private IbkrService ibkrService;
    private ReplayCandleSource source;

    @BeforeEach
    void setUp() {
        csvService = mock(CandleCsvService.class);
        ibkrService = mock(IbkrService.class);
        source = new ReplayCandleSource(csvService, ibkrService);
    }

    private Candle candle(ZonedDateTime t, double close) {
        return new Candle(t, close, close, close, close, 100L);
    }

    private List<Candle> candlesFor(LocalDate date, int count, int stepMinutes) {
        List<Candle> out = new ArrayList<>();
        ZonedDateTime start = date.atTime(14, 30).atZone(UTC);
        for (int i = 0; i < count; i++) {
            out.add(candle(start.plusMinutes((long) i * stepMinutes), 100.0 + i));
        }
        return out;
    }

    @Test
    void preload_doesNotCallIbkr_whenCsvCoversTargetDate() {
        List<Candle> cached = candlesFor(REPLAY_DATE, 78, 5);
        when(csvService.loadFromCsv("AAPL", TimeFrame.MIN_5)).thenReturn(cached);
        when(ibkrService.isConnected()).thenReturn(true);

        source.preload(REPLAY_DATE, Set.of("AAPL"), List.of(TimeFrame.MIN_5));

        verify(csvService).loadFromCsv("AAPL", TimeFrame.MIN_5);
        verify(ibkrService, never()).downloadHistoricalData(any(), any());
    }

    @Test
    void preload_backfillsFromIbkr_whenCsvMissesTargetDate() {
        when(csvService.loadFromCsv("NVDA", TimeFrame.MIN_5))
                .thenReturn(candlesFor(REPLAY_DATE.minusDays(5), 20, 5));
        List<Candle> fresh = candlesFor(REPLAY_DATE, 78, 5);
        when(ibkrService.isConnected()).thenReturn(true);
        when(ibkrService.downloadHistoricalData("NVDA", TimeFrame.MIN_5)).thenReturn(fresh);

        source.preload(REPLAY_DATE, Set.of("NVDA"), List.of(TimeFrame.MIN_5));

        verify(ibkrService).downloadHistoricalData("NVDA", TimeFrame.MIN_5);
        verify(csvService, times(1)).saveToCsv(eq("NVDA"), eq(TimeFrame.MIN_5), any());
    }

    @Test
    void preload_throws_whenDateMissingAndTwsDisconnected() {
        when(csvService.loadFromCsv("TSLA", TimeFrame.MIN_5))
                .thenReturn(candlesFor(REPLAY_DATE.minusDays(3), 5, 5));
        when(ibkrService.isConnected()).thenReturn(false);

        assertThatThrownBy(() -> source.preload(REPLAY_DATE, Set.of("TSLA"), List.of(TimeFrame.MIN_5)))
                .isInstanceOf(ReplayCandleSource.MissingDataException.class)
                .hasMessageContaining("TSLA");
    }

    @Test
    void getCandlesUntil_returnsOnlyCandlesBeforeOrEqualToVirtualNow() {
        when(csvService.loadFromCsv("AAPL", TimeFrame.MIN_5))
                .thenReturn(candlesFor(REPLAY_DATE, 10, 5));
        when(ibkrService.isConnected()).thenReturn(true);
        source.preload(REPLAY_DATE, Set.of("AAPL"), List.of(TimeFrame.MIN_5));

        ZonedDateTime virtualNow = REPLAY_DATE.atTime(14, 40).atZone(UTC); // 14:30, 14:35, 14:40
        List<Candle> visible = source.getCandlesUntil("AAPL", TimeFrame.MIN_5, virtualNow);

        assertThat(visible).hasSize(3);
        assertThat(visible.get(0).timestamp()).isEqualTo(REPLAY_DATE.atTime(14, 30).atZone(UTC));
        assertThat(visible.get(2).timestamp()).isEqualTo(virtualNow);
    }

    @Test
    void getCandlesUntil_returnsEmptyList_whenTickerNotPreloaded() {
        List<Candle> visible = source.getCandlesUntil(
                "UNKNOWN", TimeFrame.MIN_5, REPLAY_DATE.atTime(14, 45).atZone(UTC));

        assertThat(visible).isEmpty();
    }

    @Test
    void clear_evictsAllCachedData() {
        when(csvService.loadFromCsv("AAPL", TimeFrame.MIN_5))
                .thenReturn(candlesFor(REPLAY_DATE, 10, 5));
        when(ibkrService.isConnected()).thenReturn(true);
        source.preload(REPLAY_DATE, Set.of("AAPL"), List.of(TimeFrame.MIN_5));

        source.clear();

        List<Candle> visible = source.getCandlesUntil(
                "AAPL", TimeFrame.MIN_5, REPLAY_DATE.atTime(15, 0).atZone(UTC));
        assertThat(visible).isEmpty();
    }

    @Test
    void preload_reportsAllMissingTickers_whenMultipleFail() {
        when(csvService.loadFromCsv("A", TimeFrame.MIN_5))
                .thenReturn(candlesFor(REPLAY_DATE.minusDays(2), 5, 5));
        when(csvService.loadFromCsv("B", TimeFrame.MIN_5))
                .thenReturn(candlesFor(REPLAY_DATE.minusDays(2), 5, 5));
        when(ibkrService.isConnected()).thenReturn(false);

        assertThatThrownBy(() -> source.preload(REPLAY_DATE, Set.of("A", "B"), List.of(TimeFrame.MIN_5)))
                .isInstanceOf(ReplayCandleSource.MissingDataException.class)
                .hasMessageContaining("A")
                .hasMessageContaining("B");
    }
}
