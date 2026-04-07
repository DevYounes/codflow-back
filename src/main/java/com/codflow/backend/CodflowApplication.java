package com.codflow.backend;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class CodflowApplication {

    @PostConstruct
    public void init() {
        // Forcer le fuseau horaire du Maroc (UTC+1) pour tous les LocalDateTime.now()
        // et les conversions JPA, afin que les filtres par date correspondent à l'heure locale.
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Casablanca"));
    }

    public static void main(String[] args) {
        SpringApplication.run(CodflowApplication.class, args);
    }
}
