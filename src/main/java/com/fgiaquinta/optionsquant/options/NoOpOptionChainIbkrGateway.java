package com.fgiaquinta.optionsquant.options;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a no-op fallback {@link OptionChainIbkrGateway} bean.
 *
 * <p>Always returns {@code null} for every request, which causes
 * {@link OptionChainRecorderService} to silently skip persistence for that strike.
 * Replace with a real IBKR implementation to activate live greeks recording.
 */
@Slf4j
@Configuration
public class NoOpOptionChainIbkrGateway {

    @Bean
    public OptionChainIbkrGateway optionChainIbkrGateway() {
        return (ticker, strike, expiry, right) -> {
            log.debug("NoOp gateway: skipping snapshot for {}/{}/{}/{}", ticker, strike, expiry, right);
            return null;
        };
    }
}
