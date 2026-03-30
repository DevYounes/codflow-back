package com.codflow.backend.delivery.provider.ozon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
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

        // Response: { "CITIES": { "37": { "ID": 37, "NAME": "Agadir", ... }, ... } }
        JsonNode citiesNode = root.path("CITIES");
        if (citiesNode.isObject()) {
            citiesNode.fields().forEachRemaining(entry -> {
                JsonNode city = entry.getValue();
                String code = city.path("ID").asText(null);
                String name = city.path("NAME").asText(null);
                if (code != null && name != null) {
                    BigDecimal deliveryFee = parseFee(city, "PRICE", "TARIF", "LIVRAISON");
                    BigDecimal returnFee   = parseFee(city, "RETOUR", "PRICE_RETOUR", "RETOUR_TARIF");
                    cities.add(new OzonCityDto(code, name, deliveryFee, returnFee));
                }
            });
        }

        log.debug("Parsed {} cities from Ozon Express", cities.size());
        return cities;
    }

    /** Try multiple field name aliases; return first non-null numeric value found. */
    private BigDecimal parseFee(JsonNode city, String... aliases) {
        for (String alias : aliases) {
            JsonNode node = city.path(alias);
            if (!node.isMissingNode() && !node.isNull()) {
                try {
                    return new BigDecimal(node.asText().replace(",", ".").trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /** Look up the tariff for a given Ozon city ID. Returns null if not found or not cached yet. */
    public OzonCityDto findById(String cityId) {
        if (cityId == null) return null;
        return getCities().stream()
                .filter(c -> cityId.equals(c.code()))
                .findFirst()
                .orElse(null);
    }
}
