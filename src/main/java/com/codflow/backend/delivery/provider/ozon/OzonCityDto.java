package com.codflow.backend.delivery.provider.ozon;

import java.math.BigDecimal;

/**
 * @param code          Ozon city ID (used in parcel creation)
 * @param ref           Ozon city short code (e.g. "AGA")
 * @param name          Display name
 * @param deliveredPrice Prix livraison (DELIVERED-PRICE) — facturé quand le colis est livré
 * @param returnedPrice  Prix retour   (RETURNED-PRICE)  — facturé quand le colis est retourné (souvent 0)
 * @param refusedPrice   Prix refus    (REFUSED-PRICE)   — facturé quand le client refuse à la porte
 */
public record OzonCityDto(
        String code,
        String ref,
        String name,
        BigDecimal deliveredPrice,
        BigDecimal returnedPrice,
        BigDecimal refusedPrice
) {
    public OzonCityDto(String code, String name) {
        this(code, null, name, null, null, null);
    }
}
