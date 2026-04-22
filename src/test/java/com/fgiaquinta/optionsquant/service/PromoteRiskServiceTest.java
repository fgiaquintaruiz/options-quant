package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.backtest.grid.PromoteRiskRequest;
import com.fgiaquinta.optionsquant.backtest.grid.PromoteResult;
import com.fgiaquinta.optionsquant.backtest.grid.PromoteRiskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromoteRiskServiceTest {

    @Test
    @DisplayName("dryRun does not create or update ticker memory file")
    void dryRunLeavesFileAbsent(@TempDir Path temp) throws Exception {
        Path mem = temp.resolve("ticker-memory.json");
        TickerMemory tickerMemory = new TickerMemory(mem);
        PromoteRiskService svc = new PromoteRiskService(tickerMemory);

        PromoteResult r = svc.promote(new PromoteRiskRequest(
                "AAPL", "p5 continuation", false, 0.1, 0.2, true));

        assertThat(r.persisted()).isFalse();
        assertThat(r.message()).contains("dry run");
        assertThat(Files.exists(mem)).isFalse();
    }

    @Test
    @DisplayName("persist writes sl/tp overrides to JSON atomically via save")
    void persistWritesProfile(@TempDir Path temp) throws Exception {
        Path mem = temp.resolve("ticker-memory.json");
        TickerMemory tickerMemory = new TickerMemory(mem);
        PromoteRiskService svc = new PromoteRiskService(tickerMemory);

        PromoteResult r = svc.promote(new PromoteRiskRequest(
                "AAPL", "p5 continuation", false, 0.0, 0.0, false));

        assertThat(r.persisted()).isTrue();
        assertThat(Files.readString(mem)).contains("slAtrMultOverride");
        assertThat(Files.readString(mem)).contains("tpAtrMultOverride");
        assertThat(Files.readString(mem)).contains("AAPL");
        assertThat(Files.readString(mem)).contains("p5 continuation");

        TickerStrategyProfile p = tickerMemory.getStrategyProfile("AAPL", "p5 continuation");
        assertThat(p).isNotNull();
        assertThat(p.getSlAtrMultOverride()).isNotNull();
        assertThat(p.getTpAtrMultOverride()).isNotNull();
    }
}
