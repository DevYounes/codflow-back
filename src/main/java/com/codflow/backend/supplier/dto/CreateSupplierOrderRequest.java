package com.codflow.backend.supplier.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class CreateSupplierOrderRequest {

    @NotNull
    private Long supplierId;

    @NotNull
    private LocalDate orderDate;

    private LocalDate expectedDeliveryDate;

    private String notes;

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;

    @Getter
    @Setter
    public static class OrderItemRequest {
        private Long productId;
        private Long variantId;

        @NotNull
        private String productName;

        private String productSku;

        @NotNull
        @Positive
        private Integer quantityOrdered;

        @NotNull
        @Positive
        private BigDecimal unitCost;
    }
}
