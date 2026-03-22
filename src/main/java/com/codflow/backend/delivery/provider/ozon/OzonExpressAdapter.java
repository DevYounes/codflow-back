package com.codflow.backend.delivery.provider.ozon;

import com.codflow.backend.delivery.provider.DeliveryProviderAdapter;
import com.codflow.backend.delivery.provider.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ozon Express delivery provider adapter.
 * Implements the DeliveryProviderAdapter interface for Ozon Express MA API.
 *
 * API Documentation: Contact Ozon Express for API specs.
 * To support another delivery company, create a new class implementing DeliveryProviderAdapter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OzonExpressAdapter implements DeliveryProviderAdapter {

    private static final String PROVIDER_CODE = "OZON_EXPRESS";
    private static final String PROVIDER_NAME = "Ozon Express";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public String getProviderCode() {
        return PROVIDER_CODE;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public ShipmentResponse createShipment(ShipmentRequest request, ProviderConfig config) {
        try {
            WebClient client = buildClient(config);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("reference", request.getOrderNumber());
            body.put("recipient_name", request.getCustomerName());
            body.put("recipient_phone", request.getCustomerPhone());
            body.put("recipient_address", request.getAddress());
            body.put("ville", request.getVille());
            body.put("commune", request.getCity());
            body.put("cod_amount", request.getCodAmount());
            body.put("description", buildProductDescription(request));
            if (request.getNotes() != null) {
                body.put("notes", request.getNotes());
            }

            String responseBody = client.post()
                    .uri("/api/v1/shipments")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode response = objectMapper.readTree(responseBody);
            boolean success = response.path("success").asBoolean(false);

            return ShipmentResponse.builder()
                    .success(success)
                    .trackingNumber(response.path("tracking_number").asText(null))
                    .providerOrderId(response.path("id").asText(null))
                    .status(response.path("status").asText("CREATED"))
                    .message(response.path("message").asText())
                    .rawResponse(responseBody)
                    .build();

        } catch (WebClientResponseException e) {
            log.error("Ozon Express API error creating shipment for {}: {} - {}",
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

    @Override
    public PickupResponse requestPickup(PickupRequest request, ProviderConfig config) {
        try {
            WebClient client = buildClient(config);

            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode trackings = body.putArray("tracking_numbers");
            request.getTrackingNumbers().forEach(trackings::add);
            if (request.getPickupDate() != null) {
                body.put("pickup_date", request.getPickupDate().toString());
            }
            if (request.getNotes() != null) {
                body.put("notes", request.getNotes());
            }

            String responseBody = client.post()
                    .uri("/api/v1/pickups")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode response = objectMapper.readTree(responseBody);
            return PickupResponse.builder()
                    .success(response.path("success").asBoolean(false))
                    .pickupId(response.path("pickup_id").asText(null))
                    .scheduledDate(response.path("scheduled_date").asText(null))
                    .message(response.path("message").asText())
                    .build();

        } catch (Exception e) {
            log.error("Error requesting Ozon Express pickup", e);
            return PickupResponse.builder()
                    .success(false)
                    .message("Erreur demande de ramassage: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public TrackingInfo trackShipment(String trackingNumber, ProviderConfig config) {
        try {
            WebClient client = buildClient(config);

            String responseBody = client.get()
                    .uri("/api/v1/shipments/{tracking}", trackingNumber)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode response = objectMapper.readTree(responseBody);
            List<TrackingInfo.TrackingEvent> events = new ArrayList<>();

            JsonNode eventsNode = response.path("events");
            if (eventsNode.isArray()) {
                eventsNode.forEach(eventNode -> events.add(
                        TrackingInfo.TrackingEvent.builder()
                                .status(eventNode.path("status").asText())
                                .description(eventNode.path("description").asText())
                                .location(eventNode.path("location").asText(null))
                                .eventAt(LocalDateTime.now()) // Parse from API response
                                .build()
                ));
            }

            return TrackingInfo.builder()
                    .trackingNumber(trackingNumber)
                    .status(response.path("status").asText())
                    .statusDescription(response.path("status_description").asText())
                    .currentLocation(response.path("current_location").asText(null))
                    .events(events)
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

    @Override
    public boolean cancelShipment(String trackingNumber, ProviderConfig config) {
        try {
            WebClient client = buildClient(config);
            client.delete()
                    .uri("/api/v1/shipments/{tracking}", trackingNumber)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Error cancelling Ozon Express shipment {}", trackingNumber, e);
            return false;
        }
    }

    private WebClient buildClient(ProviderConfig config) {
        return webClientBuilder
                .baseUrl(config.apiBaseUrl())
                .defaultHeader("X-API-Key", config.apiKey() != null ? config.apiKey() : "")
                .build();
    }

    private String buildProductDescription(ShipmentRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return "Commande COD";
        }
        return request.getItems().stream()
                .map(i -> i.getQuantity() + "x " + i.getProductName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Commande COD");
    }
}
