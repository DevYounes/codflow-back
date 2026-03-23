package com.codflow.backend.delivery.provider.ozon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches and caches the city list from the Ozon Express public API.
 * Frontend uses this to build the city selector shown to agents during confirmation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OzonCityService {

    private static final String OZON_CITIES_URL = "https://api.ozonexpress.ma/cities";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Cacheable("ozonCities")
    public List<OzonCityDto> getCities() {
        try {
            String body = webClientBuilder.build()
                    .get()
                    .uri(OZON_CITIES_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("Ozon cities raw response: {}", body);
            return parseCities(body);
        } catch (Exception e) {
            log.error("Failed to fetch Ozon Express cities", e);
            return List.of();
        }
    }

    private List<OzonCityDto> parseCities(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<OzonCityDto> cities = new ArrayList<>();

        // Handle both array root and object with a data/cities key
        JsonNode arr = root.isArray() ? root
                     : root.has("data")   ? root.path("data")
                     : root.has("cities") ? root.path("cities")
                     : root;

        if (arr.isArray()) {
            arr.forEach(node -> {
                // Ozon uses uppercase keys: CITY_ID / CITY_NAME
                // Also handle lowercase variants: id, name
                String code = firstText(node, "CITY_ID", "city_id", "id", "ID", "code", "CODE");
                String name = firstText(node, "CITY_NAME", "city_name", "name", "NAME", "label", "LABEL");
                if (code != null && name != null) {
                    cities.add(new OzonCityDto(code, name));
                }
            });
        }

        log.debug("Parsed {} cities from Ozon Express", cities.size());
        return cities;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && !node.path(key).asText("").isBlank()) {
                return node.path(key).asText();
            }
        }
        return null;
    }
}
