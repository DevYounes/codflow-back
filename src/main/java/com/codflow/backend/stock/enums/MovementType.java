package com.codflow.backend.stock.enums;

public enum MovementType {
    IN,         // Stock entry (purchase, return)
    OUT,        // Stock exit (order fulfilled)
    ADJUSTMENT, // Manual adjustment
    RETURN      // Customer return
}
