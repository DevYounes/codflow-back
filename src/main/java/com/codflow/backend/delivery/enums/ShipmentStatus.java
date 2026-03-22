package com.codflow.backend.delivery.enums;

import lombok.Getter;

@Getter
public enum ShipmentStatus {
    PENDING("En attente d'envoi"),
    CREATED("Créé chez le transporteur"),
    PICKUP_REQUESTED("Ramassage demandé"),
    PICKED_UP("Récupéré"),
    IN_TRANSIT("En transit"),
    OUT_FOR_DELIVERY("En cours de livraison"),
    DELIVERED("Livré"),
    FAILED_DELIVERY("Tentative échouée"),
    RETURNED("Retourné"),
    CANCELLED("Annulé");

    private final String label;

    ShipmentStatus(String label) {
        this.label = label;
    }
}
