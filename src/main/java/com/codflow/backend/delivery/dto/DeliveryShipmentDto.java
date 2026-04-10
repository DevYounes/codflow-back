package com.codflow.backend.delivery.dto;

import com.codflow.backend.delivery.enums.ShipmentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class DeliveryShipmentDto {
    private Long id;
    private Long orderId;
    private String orderNumber;
    private String customerName;
    private Long providerId;
    private String providerName;
    private String trackingNumber;
    private String providerOrderId;
    private ShipmentStatus status;
    private String statusLabel;
    private boolean pickupRequested;
    private LocalDateTime pickupRequestedAt;
    private LocalDateTime shippedAt;
    private LocalDateTime outForDeliveryAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime returnedAt;
    private boolean returnReceived;
    private LocalDateTime returnReceivedAt;
    private String returnReceivedNotes;
    private String notes;
    private BigDecimal deliveredPrice;
    private BigDecimal returnedPrice;
    private BigDecimal refusedPrice;
    private BigDecimal appliedFee;
    private String appliedFeeType;
    private int cancelledAttempts;
    private int refusedAttempts;
    private List<TrackingEventDto> trackingHistory;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @Builder
    public static class TrackingEventDto {
        private String status;
        private String description;
        private String location;
        private LocalDateTime eventAt;
    }
}
