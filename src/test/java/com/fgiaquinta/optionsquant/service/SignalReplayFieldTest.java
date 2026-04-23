package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalReplayFieldTest {

    @Test
    void replay_defaultsToFalseWithLegacyConstructor() {
        Signal signal = new Signal("AAPL", "p5 continuation", "CALL",
                250.0, ZonedDateTime.now(), null);

        assertFalse(signal.replay(), "Legacy constructor must default replay=false");
    }

    @Test
    void replay_defaultsToFalseWithPatternConstructor() {
        Signal signal = new Signal("AAPL", "p5 continuation", "CALL",
                250.0, ZonedDateTime.now(), null, "squeeze_breakout + hammer");

        assertFalse(signal.replay(), "Pattern constructor must default replay=false");
    }

    @Test
    void replay_canBeSetViaFullConstructor() {
        Signal signal = new Signal("AAPL", "p5 continuation", "CALL",
                250.0, ZonedDateTime.now(), null, "squeeze_breakout + hammer", true);

        assertTrue(signal.replay(), "Full constructor with replay=true must preserve the flag");
    }
}
