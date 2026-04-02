package com.codflow.backend.charges.enums;

import lombok.Getter;

@Getter
public enum BusinessChargeType {
    PUBLICITE("Publicité / Ads"),
    LOYER("Loyer"),
    SALAIRE("Salaire"),
    ELECTRICITE("Électricité"),
    EAU("Eau"),
    INTERNET("Internet / Téléphone"),
    TRANSPORT("Transport / Carburant"),
    EMBALLAGE("Emballage / Matériel"),
    AUTRE("Autre");

    private final String label;

    BusinessChargeType(String label) {
        this.label = label;
    }
}
