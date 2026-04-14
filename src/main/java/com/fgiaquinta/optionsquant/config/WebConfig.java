package com.fgiaquinta.optionsquant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration.
 * The ChartController uses {filename:.+} pattern to handle .html extensions.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // Spring Boot 3.x removed setUseSuffixPatternMatch
    // The {filename:.+} regex pattern in ChartController handles .html files
}
