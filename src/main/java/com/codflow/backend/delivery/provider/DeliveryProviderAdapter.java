package com.codflow.backend.delivery.provider;

import com.codflow.backend.delivery.provider.dto.*;

/**
 * Strategy interface for delivery provider integrations.
 * To add a new delivery company, simply implement this interface and annotate with @Component.
 * The DeliveryProviderRegistry will auto-discover all implementations.
 */
public interface DeliveryProviderAdapter {

    /**
     * @return Unique code identifying this provider (e.g., "OZON_EXPRESS", "YALIDINE")
     */
    String getProviderCode();

    /**
     * @return Display name of the provider
     */
    String getProviderName();

    /**
     * Create a shipment with the delivery company.
     * Called when a confirmed order needs to be sent for delivery.
     */
    ShipmentResponse createShipment(ShipmentRequest request, ProviderConfig config);

    /**
     * Request a pickup from the delivery company.
     * Called to schedule collection of packages from the warehouse.
     */
    PickupResponse requestPickup(PickupRequest request, ProviderConfig config);

    /**
     * Track a shipment by its tracking number.
     * Called during scheduled sync to update delivery status.
     */
    TrackingInfo trackShipment(String trackingNumber, ProviderConfig config);

    /**
     * Cancel a shipment that was previously created.
     */
    boolean cancelShipment(String trackingNumber, ProviderConfig config);

    /**
     * Configuration holder for provider-specific settings.
     * Values come from the delivery_providers table.
     */
    record ProviderConfig(
            String apiBaseUrl,
            String apiKey,
            String apiToken,
            String extraConfig  // JSON string for provider-specific config
    ) {}
}
