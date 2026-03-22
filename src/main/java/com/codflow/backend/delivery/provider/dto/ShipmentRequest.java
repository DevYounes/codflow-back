package com.codflow.backend.delivery.provider.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class ShipmentRequest {
    private String orderNumber;
    private String customerName;
    private String customerPhone;
    private String customerPhone2;
    private String address;
    private String city;
    private String wilaya;
    private String zipCode;
    private BigDecimal codAmount;  // Amount to collect on delivery
    private BigDecimal shippingCost;
    private String notes;
    private List<ShipmentItemRequest> items;
    private int totalWeight;  // grams

    @Getter
    @Builder
    public static class ShipmentItemRequest {
        private String productName;
        private String productSku;
        private int quantity;
        private BigDecimal unitPrice;
    }
}
