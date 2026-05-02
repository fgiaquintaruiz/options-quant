package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.NewsBias;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEnrichmentTest {

    @Test
    void sixArgConstructor_defaultsNewsBiasAndEarningsAlert() {
        Signal s1 = new Signal("AAPL", "BullPut", "CALL", 150.0, ZonedDateTime.now(), null);
        assertThat(s1.newsBias()).isEqualTo(NewsBias.NEUTRAL);
        assertThat(s1.earningsAlert()).isFalse();
    }

    @Test
    void sevenArgConstructor_defaultsNewsBiasAndEarningsAlert() {
        Signal s2 = new Signal("AAPL", "BullPut", "CALL", 150.0, ZonedDateTime.now(), null, "Hammer");
        assertThat(s2.newsBias()).isEqualTo(NewsBias.NEUTRAL);
        assertThat(s2.earningsAlert()).isFalse();
    }

    @Test
    void eightArgConstructor_defaultsNewsBiasAndEarningsAlert() {
        Signal s3 = new Signal("AAPL", "BullPut", "CALL", 150.0, ZonedDateTime.now(), null, "Hammer", true);
        assertThat(s3.newsBias()).isEqualTo(NewsBias.NEUTRAL);
        assertThat(s3.earningsAlert()).isFalse();
    }

    @Test
    void tenArgConstructor_explicitNewsBiasCallAndEarningsAlertTrue() {
        Signal s4 = new Signal("AAPL", "BullPut", "CALL", 150.0, ZonedDateTime.now(), null, "Hammer", true, NewsBias.CALL, true);
        assertThat(s4.newsBias()).isEqualTo(NewsBias.CALL);
        assertThat(s4.earningsAlert()).isTrue();
    }
}
