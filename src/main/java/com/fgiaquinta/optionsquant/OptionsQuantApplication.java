package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ IbkrProperties.class, ScannerProperties.class })
public class OptionsQuantApplication {

    public static void main(String[] args) {
        SpringApplication.run(OptionsQuantApplication.class, args);
    }
}
