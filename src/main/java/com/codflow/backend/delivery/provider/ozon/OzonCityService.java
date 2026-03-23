package com.codflow.backend.delivery.provider.ozon;

import com.codflow.backend.delivery.repository.DeliveryProviderRepository;
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
 * Fetches and caches the city list from the Ozon Express API.
 * Frontend uses this to build the city selector shown to agents during confirmation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OzonCityService {

    private static final String PROVIDER_CODE = "OZON_EXPRESS";
    private static final String BASE_URL       = "https://api.ozonexpress.ma";

    private final WebClient.Builder          webClientBuilder;
    private final ObjectMapper               objectMapper;
    private final DeliveryProviderRepository providerRepository;

    @Cacheable("ozonCities")
    public List<OzonCityDto> getCities() {
        try {
            var config = providerRepository.findByCodeAndActiveTrue(PROVIDER_CODE).orElse(null);
            if (config == null) {
                log.warn("Ozon Express provider not found or inactive in delivery_providers table");
                return List.of();
            }

            String customerId = config.getApiToken();
            String apiKey     = config.getApiKey();
            String baseUrl    = config.getApiBaseUrl() != null ? config.getApiBaseUrl() : BASE_URL;

            if (customerId == null || apiKey == null) {
                log.warn("Ozon Express provider config missing customerId or apiKey");
                return List.of();
            }

            String body = webClientBuilder.build()
                    .get()
                    .uri(baseUrl + "/customers/{id}/{key}/cities", customerId, apiKey)
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
                String code = node.has("id")   ? node.path("id").asText()
                            : node.has("ID")   ? node.path("ID").asText()   : null;
                String name = node.has("name") ? node.path("name").asText()
                            : node.has("NAME") ? node.path("NAME").asText() : null;
                if (code != null && name != null) {
                    cities.add(new OzonCityDto(code, name));
                }
            });
        }
        return cities;
    }
}
