package com.codflow.backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class UpdateOrderRequest {

    @NotBlank(message = "Le nom du client est obligatoire")
    private String customerName;

    @NotBlank(message = "Le téléphone du client est obligatoire")
    private String customerPhone;

    private String customerPhone2;

    @NotBlank(message = "L'adresse est obligatoire")
    private String address;

    private String city;

    @NotBlank(message = "La ville est obligatoire")
    private String ville;

    private String zipCode;
    private String notes;
    private BigDecimal shippingCost;

    private String shopifyOrderId;
    private String externalRef;

    private List<CreateOrderRequest.OrderItemRequest> items;
}
