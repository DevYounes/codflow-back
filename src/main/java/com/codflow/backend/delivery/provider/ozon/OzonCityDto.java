package com.codflow.backend.delivery.provider.ozon;

import java.math.BigDecimal;

/**
 * @param code        Ozon city ID (used in parcel creation)
 * @param name        Display name
 * @param deliveryFee Prix livraison (PRICE field from Ozon API)
 * @param returnFee   Prix retour/refus (RETOUR field from Ozon API)
 */
public record OzonCityDto(
        String code,
        String name,
        BigDecimal deliveryFee,
        BigDecimal returnFee
) {
    /** Backwards-compatible constructor for code that only needs code+name */
    public OzonCityDto(String code, String name) {
        this(code, name, null, null);
    }
}
