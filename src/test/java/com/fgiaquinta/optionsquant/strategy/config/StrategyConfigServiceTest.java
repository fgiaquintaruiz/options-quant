package com.fgiaquinta.optionsquant.strategy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyConfigServiceTest {

    @Mock
    private StrategyConfigRepository repository;

    private StrategyConfigService service;

    @BeforeEach
    void setUp() {
        service = new StrategyConfigService(repository);
    }

    @Test
    void getActiveLive_returnsOnlyEnabledLive() {
        when(repository.findActiveLive()).thenReturn(List.of("p6 reversal", "p1 squeeze"));
        assertThat(service.getActiveLive()).containsExactly("p6 reversal", "p1 squeeze");
    }

    @Test
    void getActiveBacktest_returnsOnlyEnabledBacktest() {
        when(repository.findActiveBacktest()).thenReturn(List.of("c1 squeeze", "c2 trend"));
        assertThat(service.getActiveBacktest()).containsExactly("c1 squeeze", "c2 trend");
    }

    @Test
    void isLiveEnabled_returnsTrueWhenActive() {
        when(repository.findActiveLive()).thenReturn(List.of("p6 reversal"));
        assertThat(service.isLiveEnabled("p6 reversal")).isTrue();
    }

    @Test
    void isLiveEnabled_returnsFalseWhenInactive() {
        when(repository.findActiveLive()).thenReturn(List.of("p6 reversal"));
        assertThat(service.isLiveEnabled("c3 bounce")).isFalse();
    }

    @Test
    void isLiveEnabled_returnsFalseWhenListEmpty() {
        when(repository.findActiveLive()).thenReturn(List.of());
        assertThat(service.isLiveEnabled("p6 reversal")).isFalse();
    }

    @Test
    void update_delegatesToRepository() {
        when(repository.update("p6 reversal", true, true, "test", "user")).thenReturn(1);
        int result = service.update("p6 reversal", true, true, "test", "user");
        assertThat(result).isEqualTo(1);
        verify(repository).update("p6 reversal", true, true, "test", "user");
    }

    @Test
    void findByName_returnsEmptyIfNotExists() {
        when(repository.findByName("unknown")).thenReturn(Optional.empty());
        assertThat(service.findByName("unknown")).isEmpty();
    }

    // --- partialUpdate ---

    private static final StrategyConfig EXISTING = StrategyConfig.builder()
            .strategyName("p6 reversal")
            .enabledLive(true)
            .enabledBacktest(false)
            .notes("note")
            .updatedAt(1000L)
            .updatedBy("user1")
            .build();

    @Test
    void partialUpdate_returnsEmptyWhenNameNotFound() {
        when(repository.findByName("ghost")).thenReturn(Optional.empty());
        assertThat(service.partialUpdate("ghost", true, null)).isEmpty();
    }

    @Test
    void partialUpdate_onlyEnabledLive_keepsExistingBacktest() {
        StrategyConfig afterUpdate = EXISTING.toBuilder().enabledLive(false).build();
        when(repository.findByName("p6 reversal"))
                .thenReturn(Optional.of(EXISTING))
                .thenReturn(Optional.of(afterUpdate));

        Optional<StrategyConfig> result = service.partialUpdate("p6 reversal", false, null);

        assertThat(result).isPresent();
        assertThat(result.get().isEnabledLive()).isFalse();
        assertThat(result.get().isEnabledBacktest()).isFalse(); // unchanged
        verify(repository).update("p6 reversal", false, false, "note", "user1");
    }

    @Test
    void partialUpdate_onlyEnabledBacktest_keepsExistingLive() {
        StrategyConfig afterUpdate = EXISTING.toBuilder().enabledBacktest(true).build();
        when(repository.findByName("p6 reversal"))
                .thenReturn(Optional.of(EXISTING))
                .thenReturn(Optional.of(afterUpdate));

        Optional<StrategyConfig> result = service.partialUpdate("p6 reversal", null, true);

        assertThat(result).isPresent();
        assertThat(result.get().isEnabledBacktest()).isTrue();
        assertThat(result.get().isEnabledLive()).isTrue(); // unchanged
        verify(repository).update("p6 reversal", true, true, "note", "user1");
    }

    @Test
    void partialUpdate_bothFields_appliesBoth() {
        StrategyConfig afterUpdate = EXISTING.toBuilder().enabledLive(false).enabledBacktest(true).build();
        when(repository.findByName("p6 reversal"))
                .thenReturn(Optional.of(EXISTING))
                .thenReturn(Optional.of(afterUpdate));

        Optional<StrategyConfig> result = service.partialUpdate("p6 reversal", false, true);

        assertThat(result).isPresent();
        assertThat(result.get().isEnabledLive()).isFalse();
        assertThat(result.get().isEnabledBacktest()).isTrue();
        verify(repository).update("p6 reversal", true, false, "note", "user1");
    }
}
