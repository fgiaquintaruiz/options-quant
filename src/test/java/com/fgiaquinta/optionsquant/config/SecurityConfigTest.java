package com.fgiaquinta.optionsquant.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link SecurityConfig} contributes an explicit {@link SecurityFilterChain}
 * to the context and overrides Spring Security's default lockdown.
 *
 * <p>Uses {@link WebApplicationContextRunner} (lightweight) instead of {@code @SpringBootTest}
 * so the test does not boot the full application context (which connects to IBKR TWS).
 */
class SecurityConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(SecurityConfig.class);

    @Test
    void filterChainBeanIsRegistered() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context.getBean(SecurityFilterChain.class)).isNotNull();
        });
    }
}
