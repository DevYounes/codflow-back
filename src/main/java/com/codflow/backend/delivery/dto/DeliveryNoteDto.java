package com.codflow.backend.delivery.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder
public class DeliveryNoteDto {

    private Long id;
    private String ref;
    private Long providerId;
    private String providerName;
    private String status;
    private String notes;

    private int shipmentCount;
    private List<ShipmentSummary> shipments;

    /** PDF Standard */
    private String pdfUrl;
    /** Étiquettes A4 */
    private String pdfTicketsA4Url;
    /** Étiquettes 10x10cm */
    private String pdfTickets10x10Url;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @Setter
    @Builder
    public static class ShipmentSummary {
        private Long shipmentId;
        private Long orderId;
        private String orderNumber;
        private String customerName;
        private String trackingNumber;
        private String status;
    }
}
