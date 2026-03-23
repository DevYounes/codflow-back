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

            return parseCities(body);
        } catch (Exception e) {
            log.error("Failed to fetch Ozon Express cities", e);
            return List.of();
        }
    }

    private List<OzonCityDto> parseCities(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<OzonCityDto> cities = new ArrayList<>();

        // Handle both array root and object with a data key
        JsonNode arr = root.isArray() ? root : root.path("data");

        if (arr.isArray()) {
            arr.forEach(node -> {
                String id   = node.has("id")   ? node.path("id").asText()
                            : node.has("ID")   ? node.path("ID").asText()   : null;
                String name = node.has("name") ? node.path("name").asText()
                            : node.has("NAME") ? node.path("NAME").asText() : null;
                if (id != null && name != null) {
                    cities.add(new OzonCityDto(id, name));
                }
            });
        }
        return cities;
    }
}
