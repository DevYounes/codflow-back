package com.codflow.backend.supplier.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class SupplierDto {
    private Long id;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String notes;
    private boolean active;
    /** Montant total commandé (hors annulations). */
    private BigDecimal totalOrdered;
    /** Montant total payé. */
    private BigDecimal totalPaid;
    /** Solde restant. */
    private BigDecimal totalRemaining;
    private LocalDateTime createdAt;
}
