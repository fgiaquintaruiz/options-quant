package com.fgiaquinta.optionsquant.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NewsBiasTest {

    @Test
    void shouldContainExactlyCallPutNeutral() {
        Set<String> names = Arrays.stream(NewsBias.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("CALL", "PUT", "NEUTRAL");
    }

    @Test
    void neutralNameShouldBeNeutral() {
        assertThat(NewsBias.NEUTRAL.name()).isEqualTo("NEUTRAL");
    }
}
