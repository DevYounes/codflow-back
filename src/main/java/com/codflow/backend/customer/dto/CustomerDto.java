package com.codflow.backend.customer.dto;

import com.codflow.backend.customer.enums.CustomerStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CustomerDto {
    private Long id;
    private String fullName;
    private String phone;
    private String email;
    private String address;
    private String ville;
    private CustomerStatus status;
    private String statusLabel;
    private String notes;
    private LocalDateTime createdAt;

    // Statistiques commandes
    private long totalOrders;
    private long confirmedOrders;
    private long cancelledOrders;
    private double confirmationRate;
    private LocalDateTime lastOrderDate;
}
