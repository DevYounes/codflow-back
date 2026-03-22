package com.codflow.backend.team.enums;

public enum Role {
    ADMIN,      // Full access - manage everything
    MANAGER,    // Manage agents, view all orders, assign orders
    AGENT       // Confirm assigned orders, update statuses
}
