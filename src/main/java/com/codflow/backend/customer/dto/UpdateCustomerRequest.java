package com.codflow.backend.customer.dto;

import com.codflow.backend.customer.enums.CustomerStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCustomerRequest {
    private String fullName;
    private String email;
    private String address;
    private String ville;
    private CustomerStatus status;
    private String notes;
}
