package com.codflow.backend.delivery.provider.ozon;

import com.codflow.backend.common.util.PhoneNormalizer;
import com.codflow.backend.delivery.provider.DeliveryProviderAdapter;
import com.codflow.backend.delivery.provider.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ozon Express delivery provider adapter.
 *
 * API base: https://api.ozonexpress.ma
 * Auth: credentials in URL path — /customers/{customerId}/{apiKey}/...
 *
 * Provider config mapping (delivery_providers table):
 *   api_base_url = https://api.ozonexpress.ma
 *   api_key      = Your Ozon API key
 *   api_token    = Your Ozon customer ID (the {YOUR_ID} part of the URL)
 *
 * To support another delivery company, create a new class implementing DeliveryProviderAdapter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OzonExpressAdapter implements DeliveryProviderAdapter {

    private static final String PROVIDER_CODE = "OZON_EXPRESS";
    private static final String PROVIDER_NAME = "Ozon Express";
    private static final String BASE_URL      = "https://api.ozonexpress.ma";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final OzonExpressProperties properties;

    @Override
    public String getProviderCode() { return PROVIDER_CODE; }

    @Override
    public String getProviderName() { return PROVIDER_NAME; }

    // -------------------------------------------------------------------------
    // Create shipment — POST /customers/{id}/{key}/add-parcel (form-data)
    // -------------------------------------------------------------------------
    @Override
    public ShipmentResponse createShipment(ShipmentRequest request, ProviderConfig config) {
        try {
            // DB values take precedence; fall back to environment variables
            String customerId = StringUtils.hasText(config.apiToken())   ? config.apiToken()   : properties.getCustomerId();
            String apiKey     = StringUtils.hasText(config.apiKey())     ? config.apiKey()     : properties.getApiKey();
            String baseUrl    = StringUtils.hasText(config.apiBaseUrl()) ? config.apiBaseUrl() : properties.getApiBaseUrl();

            if (!StringUtils.hasText(customerId) || !StringUtils.hasText(apiKey)) {
                return ShipmentResponse.builder()
                        .success(false)
                        .message("Configuration Ozon Express incomplète (customerId / apiKey manquant). Définir OZON_CUSTOMER_ID et OZON_API_KEY.")
                        .build();
            }

            // deliveryCityId must be set — warn if missing
            String cityId = request.getDeliveryCityId();
            if (cityId == null || cityId.isBlank()) {
                log.warn("deliveryCityId not set for order {}; parcel-city will be empty", request.getOrderNumber());
                cityId = "";
            }

            // Build form-data
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("parcel-receiver", request.getCustomerName());
            form.add("parcel-phone",    PhoneNormalizer.toLocalFormat(request.getCustomerPhone()));
            form.add("parcel-city",     cityId);
            form.add("parcel-address",  request.getAddress());
            form.add("parcel-price",    request.getCodAmount().toPlainString());
            form.add("parcel-stock",    "0");   // 0 = ramassage (pickup by Ozon)
            form.add("parcel-open",     "1");   // allow opening by default

            if (request.getNotes() != null && !request.getNotes().isBlank()) {
                form.add("parcel-note", request.getNotes());
            }
            if (request.getItems() != null && !request.getItems().isEmpty()) {
                form.add("parcel-nature", buildNature(request));
                form.add("products",      buildProductsJson(request));
            }

            WebClient client = webClientBuilder.baseUrl(baseUrl).build();

            String responseBody = client.post()
                    .uri("/customers/{id}/{key}/add-parcel", customerId, apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode parcel = json.path("ADD-PARCEL").path("NEW-PARCEL");
            String trackingNumber = parcel.path("TRACKING-NUMBER").asText(null);
            boolean success = trackingNumber != null && !trackingNumber.isBlank();

            return ShipmentResponse.builder()
                    .success(success)
                    .trackingNumber(trackingNumber)
                    .providerOrderId(trackingNumber)
                    .status(success ? "CREATED" : "FAILED")
                    .message(success
                            ? "Colis créé — " + parcel.path("CITY_ID").asText("")
                            : "Réponse Ozon inattendue: " + responseBody)
                    .rawResponse(responseBody)
                    .build();

        } catch (WebClientResponseException e) {
            log.error("Ozon Express API error creating shipment for {}: {} — {}",
                    request.getOrderNumber(), e.getStatusCode(), e.getResponseBodyAsString());
            return ShipmentResponse.builder()
                    .success(false)
                    .message("Erreur API Ozon Express: " + e.getStatusCode())
                    .rawResponse(e.getResponseBodyAsString())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error creating Ozon Express shipment for {}", request.getOrderNumber(), e);
            return ShipmentResponse.builder()
                    .success(false)
                    .message("Erreur inattendue: " + e.getMessage())
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Request pickup — not part of the documented API; kept as stub
    // -------------------------------------------------------------------------
    @Override
    public PickupResponse requestPickup(PickupRequest request, ProviderConfig config) {
        // Ozon Express does not expose a pickup API in the current documentation.
        // Pickup is handled automatically once the parcel is registered.
        log.info("Ozon Express pickup requested for {} parcels (handled automatically by Ozon)", request.getTrackingNumbers().size());
        return PickupResponse.builder()
                .success(true)
                .message("Ozon Express gère le ramassage automatiquement après enregistrement du colis")
                .build();
    }

    // -------------------------------------------------------------------------
    // Track shipment — GET /customers/{id}/{key}/get-parcel/{tracking}
    // -------------------------------------------------------------------------
    @Override
    public TrackingInfo trackShipment(String trackingNumber, ProviderConfig config) {
        try {
            String customerId = StringUtils.hasText(config.apiToken())   ? config.apiToken()   : properties.getCustomerId();
            String apiKey     = StringUtils.hasText(config.apiKey())     ? config.apiKey()     : properties.getApiKey();
            String baseUrl    = StringUtils.hasText(config.apiBaseUrl()) ? config.apiBaseUrl() : properties.getApiBaseUrl();

            if (!StringUtils.hasText(customerId) || !StringUtils.hasText(apiKey)) {
                log.error("Ozon Express credentials not configured for tracking {}", trackingNumber);
                return TrackingInfo.builder().trackingNumber(trackingNumber).status("UNKNOWN")
                        .statusDescription("Credentials Ozon Express non configurés").events(List.of()).build();
            }

            WebClient client = webClientBuilder.baseUrl(baseUrl).build();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("tracking-number", trackingNumber);

            String responseBody = client.post()
                    .uri("/customers/{id}/{key}/tracking", customerId, apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(responseBody);
            List<TrackingInfo.TrackingEvent> events = new ArrayList<>();

            JsonNode history = json.path("HISTORY");
            if (history.isArray()) {
                history.forEach(node -> events.add(
                        TrackingInfo.TrackingEvent.builder()
                                .status(node.path("STATUS").asText())
                                .description(node.path("COMMENT").asText(null))
                                .location(node.path("CITY").asText(null))
                                .eventAt(LocalDateTime.now())
                                .build()
                ));
            }

            return TrackingInfo.builder()
                    .trackingNumber(trackingNumber)
                    .status(json.path("STATUS").asText("UNKNOWN"))
                    .statusDescription(json.path("STATUS_LABEL").asText(null))
                    .currentLocation(json.path("CITY_NAME").asText(null))
                    .events(events)
                    .build();

        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.NotFound e) {
            log.warn("Ozon Express: colis introuvable pour le tracking {} (404) — probable ancien numéro invalide", trackingNumber);
            return TrackingInfo.builder()
                    .trackingNumber(trackingNumber)
                    .status("NOT_FOUND")
                    .statusDescription("Colis introuvable chez Ozon Express")
                    .events(List.of())
                    .build();
        } catch (Exception e) {
            log.error("Error tracking Ozon Express shipment {}", trackingNumber, e);
            return TrackingInfo.builder()
                    .trackingNumber(trackingNumber)
                    .status("UNKNOWN")
                    .statusDescription("Impossible de récupérer le statut")
                    .events(List.of())
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Cancel shipment — not documented; stub returning false
    // -------------------------------------------------------------------------
    @Override
    public boolean cancelShipment(String trackingNumber, ProviderConfig config) {
        log.warn("Ozon Express cancel not supported via API for tracking {}", trackingNumber);
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildNature(ShipmentRequest request) {
        return request.getItems().stream()
                .map(i -> i.getQuantity() + "x " + i.getProductName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Commande COD");
    }

    /**
     * Builds the 'products' JSON array expected by Ozon:
     * [{"ref": "SKU001", "qnty": 2}, ...]
     */
    private String buildProductsJson(ShipmentRequest request) {
        try {
            var arr = objectMapper.createArrayNode();
            if (request.getItems() != null && !request.getItems().isEmpty()) {
                for (var item : request.getItems()) {
                    var node = objectMapper.createObjectNode();
                    node.put("ref",  item.getProductSku() != null ? item.getProductSku() : item.getProductName());
                    node.put("qnty", item.getQuantity());
                    arr.add(node);
                }
            } else {
                // Fallback: Ozon requires at least one product entry for stock parcels
                var node = objectMapper.createObjectNode();
                node.put("ref",  request.getOrderNumber() != null ? request.getOrderNumber() : "COMMANDE");
                node.put("qnty", 1);
                arr.add(node);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("Could not serialize products JSON for Ozon", e);
            return "[{\"ref\":\"COMMANDE\",\"qnty\":1}]";
        }
    }
}
