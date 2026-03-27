package com.codflow.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Accepts both date-only ("yyyy-MM-dd") and full datetime ("yyyy-MM-ddTHH:mm:ss")
 * strings when binding LocalDateTime @RequestParam across the whole application.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, LocalDateTime.class, source -> {
            String s = source.trim();
            if (s.isEmpty()) return null;
            // Date-only: treat as start of day
            if (s.length() == 10) {
                return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            }
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        });
    }
}
