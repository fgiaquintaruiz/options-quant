package com.fgiaquinta.optionsquant.strategy.config;

import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StrategyConfigController.class)
class StrategyConfigControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    StrategyConfigService strategyConfigService;

    // GlobalExceptionHandler (com.fgiaquinta.optionsquant.controller) depends on MetricsService — mock it here
    @MockitoBean
    MetricsService metricsService;

    private static final StrategyConfig CONFIG_A = StrategyConfig.builder()
            .strategyName("p6 reversal")
            .enabledLive(true)
            .enabledBacktest(false)
            .notes("note-a")
            .updatedAt(1000L)
            .updatedBy("user1")
            .build();

    private static final StrategyConfig CONFIG_B = StrategyConfig.builder()
            .strategyName("c1 squeeze")
            .enabledLive(false)
            .enabledBacktest(true)
            .notes("note-b")
            .updatedAt(2000L)
            .updatedBy("user2")
            .build();

    // --- GET /api/strategy-config ---

    @Test
    void findAll_returns200WithList() throws Exception {
        when(strategyConfigService.findAll()).thenReturn(List.of(CONFIG_A, CONFIG_B));

        mockMvc.perform(get("/api/strategy-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].strategyName").value("p6 reversal"))
                .andExpect(jsonPath("$[0].enabledLive").value(true))
                .andExpect(jsonPath("$[0].enabledBacktest").value(false))
                .andExpect(jsonPath("$[1].strategyName").value("c1 squeeze"))
                .andExpect(jsonPath("$[1].enabledLive").value(false))
                .andExpect(jsonPath("$[1].enabledBacktest").value(true));
    }

    @Test
    void findAll_returnsEmptyList_when_noStrategies() throws Exception {
        when(strategyConfigService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/strategy-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void findAll_callsServiceFindAll() throws Exception {
        when(strategyConfigService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/strategy-config"));

        verify(strategyConfigService, times(1)).findAll();
    }

    // --- PATCH /api/strategy-config/{name} ---

    @Test
    void update_enabledLive_returns200WithUpdatedStrategy() throws Exception {
        StrategyConfig updated = CONFIG_A.toBuilder().enabledLive(false).build();
        when(strategyConfigService.partialUpdate(eq("p6 reversal"), eq(false), any()))
                .thenReturn(Optional.of(updated));

        mockMvc.perform(patch("/api/strategy-config/p6 reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledLive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyName").value("p6 reversal"))
                .andExpect(jsonPath("$.enabledLive").value(false));
    }

    @Test
    void update_enabledBacktest_returns200WithUpdatedStrategy() throws Exception {
        StrategyConfig updated = CONFIG_B.toBuilder().enabledBacktest(false).build();
        when(strategyConfigService.partialUpdate(eq("c1 squeeze"), any(), eq(false)))
                .thenReturn(Optional.of(updated));

        mockMvc.perform(patch("/api/strategy-config/c1 squeeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledBacktest\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyName").value("c1 squeeze"))
                .andExpect(jsonPath("$.enabledBacktest").value(false));
    }

    @Test
    void update_unknownName_returns404() throws Exception {
        when(strategyConfigService.partialUpdate(eq("unknown"), any(), any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/strategy-config/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledLive\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_partialBody_onlyEnabledLive_doesNotTouchBacktest() throws Exception {
        StrategyConfig updated = CONFIG_A.toBuilder().enabledLive(false).build();
        when(strategyConfigService.partialUpdate("p6 reversal", false, null))
                .thenReturn(Optional.of(updated));

        // Body only contains enabledLive — enabledBacktest absent (deserializes to null)
        mockMvc.perform(patch("/api/strategy-config/p6 reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledLive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledBacktest").value(false)); // original value unchanged
    }

    @Test
    void update_callsServiceUpdate_withCorrectArgs() throws Exception {
        when(strategyConfigService.partialUpdate("p6 reversal", true, null))
                .thenReturn(Optional.of(CONFIG_A));

        mockMvc.perform(patch("/api/strategy-config/p6 reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledLive\":true}"));

        verify(strategyConfigService, times(1)).partialUpdate("p6 reversal", true, null);
    }
}
