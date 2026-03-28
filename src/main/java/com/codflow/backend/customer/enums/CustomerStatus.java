package com.codflow.backend.customer.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CustomerStatus {
    ACTIVE("Actif"),
    FIDELE("Fidèle"),
    NON_SERIEUX("Non sérieux"),
    BLACKLISTED("Blacklisté");

    private final String label;
}
