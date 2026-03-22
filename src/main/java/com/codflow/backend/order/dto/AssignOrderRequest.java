package com.codflow.backend.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AssignOrderRequest {

    @NotNull(message = "L'agent est obligatoire")
    private Long agentId;

    private List<Long> orderIds; // For bulk assignment
}
