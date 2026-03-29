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
            String customerId = resolveCustomerId(config);
            String apiKey     = resolveApiKey(config);
            String baseUrl    = resolveBaseUrl(config);

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
            String customerId = resolveCustomerId(config);
            String apiKey     = resolveApiKey(config);
            String baseUrl    = resolveBaseUrl(config);

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
            JsonNode tracking = json.path("TRACKING");

            // Check API-level error
            String result = tracking.path("RESULT").asText("ERROR");
            if (!"SUCCESS".equals(result)) {
                return TrackingInfo.builder().trackingNumber(trackingNumber).status("UNKNOWN")
                        .statusDescription(tracking.path("MESSAGE").asText("Erreur tracking")).events(List.of()).build();
            }

            // Parse HISTORY: object with numeric keys {"1": {...}, "2": {...}}
            List<TrackingInfo.TrackingEvent> events = new ArrayList<>();
            JsonNode history = tracking.path("HISTORY");
            history.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                events.add(TrackingInfo.TrackingEvent.builder()
                        .status(node.path("STATUT").asText())
                        .description(node.path("COMMENT").asText(null))
                        .location(null)
                        .eventAt(parseOzonDate(node))
                        .build());
            });

            // Sort events by date ascending
            events.sort(java.util.Comparator.comparing(TrackingInfo.TrackingEvent::getEventAt));

            // Current status from LAST_TRACKING
            JsonNode lastTracking = tracking.path("LAST_TRACKING");
            String currentStatut = lastTracking.path("STATUT").asText("UNKNOWN");

            return TrackingInfo.builder()
                    .trackingNumber(trackingNumber)
                    .status(currentStatut)
                    .statusDescription(currentStatut)
                    .currentLocation(null)
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
    // Delivery Note — 3-step flow (create → add parcels → save)
    // -------------------------------------------------------------------------

    /**
     * Step 1: Create an empty Bon de Livraison.
     * GET /customers/{id}/{key}/add-delivery-note
     * Returns: { "ref": "BL240115001", ... }
     */
    public String createDeliveryNote(ProviderConfig config) {
        String customerId = resolveCustomerId(config);
        String apiKey     = resolveApiKey(config);
        String baseUrl    = resolveBaseUrl(config);

        if (!StringUtils.hasText(customerId) || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Credentials Ozon Express non configurés (customerId / apiKey manquant)");
        }

        try {
            WebClient client = webClientBuilder.baseUrl(baseUrl).build();
            String responseBody = client.post()
                    .uri("/customers/{id}/{key}/add-delivery-note", customerId, apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(responseBody);
            String ref = json.path("ref").asText(null);
            if (!StringUtils.hasText(ref)) {
                throw new IllegalStateException("Ozon Express n'a pas retourné de référence BL. Réponse: " + responseBody);
            }
            log.info("Ozon Express delivery note created: ref={}", ref);
            return ref;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating Ozon Express delivery note: {}", e.getMessage(), e);
            throw new IllegalStateException("Erreur lors de la création du BL: " + e.getMessage());
        }
    }

    /**
     * Step 2: Add tracking numbers (colis) to the BL.
     * POST /customers/{id}/{key}/add-parcel-to-delivery-note
     * Form params: Ref={ref} + Codes[0]=TN1 + Codes[1]=TN2 + ...
     */
    public void addParcelsToDeliveryNote(String ref, List<String> trackingNumbers, ProviderConfig config) {
        String customerId = resolveCustomerId(config);
        String apiKey     = resolveApiKey(config);
        String baseUrl    = resolveBaseUrl(config);

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("Ref", ref);
            for (int i = 0; i < trackingNumbers.size(); i++) {
                form.add("Codes[" + i + "]", trackingNumbers.get(i));
            }

            WebClient client = webClientBuilder.baseUrl(baseUrl).build();
            String responseBody = client.post()
                    .uri("/customers/{id}/{key}/add-parcel-to-delivery-note", customerId, apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Ozon Express add parcels to BL {}: {}", ref, responseBody);
        } catch (Exception e) {
            log.error("Error adding parcels to Ozon Express delivery note {}: {}", ref, e.getMessage(), e);
            throw new IllegalStateException("Erreur lors de l'ajout des colis au BL: " + e.getMessage());
        }
    }

    /**
     * Step 3: Save (finalize) the BL.
     * POST /customers/{id}/{key}/save-delivery-note
     * Form params: Ref={ref}
     */
    public void saveDeliveryNote(String ref, ProviderConfig config) {
        String customerId = resolveCustomerId(config);
        String apiKey     = resolveApiKey(config);
        String baseUrl    = resolveBaseUrl(config);

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("Ref", ref);

            WebClient client = webClientBuilder.baseUrl(baseUrl).build();
            String responseBody = client.post()
                    .uri("/customers/{id}/{key}/save-delivery-note", customerId, apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Ozon Express delivery note {} saved: {}", ref, responseBody);
        } catch (Exception e) {
            log.error("Error saving Ozon Express delivery note {}: {}", ref, e.getMessage(), e);
            throw new IllegalStateException("Erreur lors de la sauvegarde du BL: " + e.getMessage());
        }
    }

    /** Build the PDF URLs for a given BL ref (no API call needed). */
    public record DeliveryNotePdfs(String standard, String ticketsA4, String tickets10x10) {}

    public DeliveryNotePdfs getPdfUrls(String ref) {
        String base = "https://client.ozoneexpress.ma";
        return new DeliveryNotePdfs(
                base + "/pdf-delivery-note?dn-ref=" + ref,
                base + "/pdf-delivery-note-tickets?dn-ref=" + ref,
                base + "/pdf-delivery-note-tickets-4-4?dn-ref=" + ref
        );
    }

    // -------------------------------------------------------------------------
    // Credential helpers
    // -------------------------------------------------------------------------

    private String resolveCustomerId(ProviderConfig config) {
        return StringUtils.hasText(config.apiToken()) ? config.apiToken() : properties.getCustomerId();
    }

    private String resolveApiKey(ProviderConfig config) {
        return StringUtils.hasText(config.apiKey()) ? config.apiKey() : properties.getApiKey();
    }

    private String resolveBaseUrl(ProviderConfig config) {
        return StringUtils.hasText(config.apiBaseUrl()) ? config.apiBaseUrl() : properties.getApiBaseUrl();
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

    private LocalDateTime parseOzonDate(JsonNode node) {
        // Try TIME_STR first: "2026-03-25 00:44"
        String timeStr = node.path("TIME_STR").asText(null);
        if (timeStr != null && !timeStr.isBlank()) {
            try {
                return LocalDateTime.parse(timeStr,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (Exception ignored) {}
        }
        // Fallback: TIME unix timestamp
        String unixStr = node.path("TIME").asText(null);
        if (unixStr != null && !unixStr.isBlank()) {
            try {
                return java.time.Instant.ofEpochSecond(Long.parseLong(unixStr))
                        .atZone(java.time.ZoneId.of("Africa/Casablanca"))
                        .toLocalDateTime();
            } catch (Exception ignored) {}
        }
        return LocalDateTime.now();
    }

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
