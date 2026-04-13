package com.codflow.backend.order.dto;

import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CreateOrderRequest {

    private String orderNumber;

    private OrderSource source = OrderSource.MANUAL;

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
    private String deliveryNotes;

    private BigDecimal shippingCost = BigDecimal.ZERO;

    private String shopifyOrderId;
    private String externalRef;

    private Long assignedToId;
    private String deliveryCityId;

    /** Statut initial optionnel. Si null → NOUVEAU. */
    private OrderStatus status;

    @NotEmpty(message = "La commande doit contenir au moins un article")
    @Valid
    private List<OrderItemRequest> items;

    @Getter
    @Setter
    public static class OrderItemRequest {
        private Long productId;
        private Long variantId;

        @NotBlank(message = "Le nom du produit est obligatoire")
        private String productName;

        private String productSku;
        private String variantColor;
        private String variantSize;

        @NotNull
        private Integer quantity = 1;

        @NotNull
        private BigDecimal unitPrice;
    }
}
