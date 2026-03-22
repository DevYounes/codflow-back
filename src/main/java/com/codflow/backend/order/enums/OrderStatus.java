package com.codflow.backend.order.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {

    // Initial states
    NOUVEAU("Nouveau", false, false),

    // Confirmation attempts
    APPEL_1("Appel 1", false, false),
    APPEL_2("Appel 2", false, false),
    APPEL_3("Appel 3", false, false),
    MESSAGE_WHATSAPP("Message WhatsApp", false, false),
    APPEL_PLUS_MESSAGE("Appel + Message WhatsApp", false, false),

    // No-contact states
    PAS_DE_REPONSE("Pas de réponse", false, false),
    INJOIGNABLE("Injoignable", false, false),
    BOITE_VOCAL("Boîte vocale", false, false),
    REPORTE("Reporté", false, false),

    // Terminal positive
    CONFIRME("Confirmé", true, false),

    // Terminal negative
    ANNULE("Annulé", false, true),
    PAS_SERIEUX("Pas sérieux", false, true),
    FAKE_ORDER("Fake order", false, true),
    DOUBLON("Doublon", false, true),

    // Delivery states (after confirmation)
    EN_PREPARATION("En préparation", false, false),
    ENVOYE("Envoyé", false, false),
    EN_LIVRAISON("En livraison", false, false),
    LIVRE("Livré", true, false),
    RETOURNE("Retourné", false, true),
    ECHEC_LIVRAISON("Échec de livraison", false, false);

    private final String label;
    private final boolean positive; // Represents a successful outcome
    private final boolean terminal; // No more status changes expected (negative)

    OrderStatus(String label, boolean positive, boolean terminal) {
        this.label = label;
        this.positive = positive;
        this.terminal = terminal;
    }

    public boolean isConfirmed() {
        return this == CONFIRME;
    }

    public boolean isCancelled() {
        return this == ANNULE || this == PAS_SERIEUX || this == FAKE_ORDER || this == DOUBLON;
    }

    public boolean isDelivered() {
        return this == LIVRE;
    }

    public boolean isInDelivery() {
        return this == EN_LIVRAISON || this == ENVOYE || this == EN_PREPARATION;
    }
}
