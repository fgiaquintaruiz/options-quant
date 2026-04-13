package com.fgiaquinta.optionsquant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC configuration to serve static files from backtest/charts directory.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve backtest charts from the backtest/charts directory
        Path chartsDir = Paths.get("backtest/charts").toAbsolutePath();
        registry.addResourceHandler("/backtest/charts/**")
                .addResourceLocations("file:" + chartsDir.toString() + "/");
    }
}
