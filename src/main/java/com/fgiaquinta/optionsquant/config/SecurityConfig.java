package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal explicit security configuration.
 *
 * <p>Context: single-user trading bot bound to 127.0.0.1 (see {@code application.yml}
 * {@code server.address}). Network exposure is mitigated at the bind layer.
 *
 * <p>This filter chain exists so Spring Security defaults (which would gate every
 * endpoint with HTTP Basic) do NOT activate now that the starter is on the classpath.
 * CSRF is disabled because the API is REST (no cookie-based session) and the React UI
 * is served same-origin from this same JVM.
 *
 * <p>Future hardening hook: replace {@code permitAll()} with an auth scheme if a
 * multi-user mode is ever introduced.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
