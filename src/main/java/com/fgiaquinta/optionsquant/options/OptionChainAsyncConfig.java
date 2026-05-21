package com.fgiaquinta.optionsquant.options;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Spring async configuration for option chain recording.
 *
 * <p>A dedicated thread pool ({@code optionChainExecutor}) is used so that
 * option chain I/O never competes with the main scan / signal pipeline executors.
 *
 * <ul>
 *   <li>Core 2: minimum threads always available for market-hours recording.</li>
 *   <li>Max 10: handles burst of 16 tickers × 2 directions without exhaustion.</li>
 *   <li>Queue 50: absorbs brief IBKR back-pressure without dropping snapshots.</li>
 * </ul>
 */
@EnableAsync
@Configuration
public class OptionChainAsyncConfig {

    @Bean("optionChainExecutor")
    public Executor optionChainExecutor() {
        final ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("optchain-");
        exec.initialize();
        return exec;
    }
}
