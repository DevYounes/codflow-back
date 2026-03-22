package com.codflow.backend.delivery.provider.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class TrackingInfo {
    private String trackingNumber;
    private String status;
    private String statusDescription;
    private String currentLocation;
    private LocalDateTime estimatedDelivery;
    private List<TrackingEvent> events;

    @Getter
    @Builder
    public static class TrackingEvent {
        private String status;
        private String description;
        private String location;
        private LocalDateTime eventAt;
    }
}
