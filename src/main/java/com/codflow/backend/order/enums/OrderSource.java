package com.codflow.backend.order.enums;

/**
 * Canal d'entrée d'une commande dans CODflow.
 *
 * <ul>
 *   <li>{@link #SHOPIFY} — importée depuis Shopify via scheduler since_id</li>
 *   <li>{@link #EXCEL} — importée manuellement via fichier Excel</li>
 *   <li>{@link #MANUAL} — créée dans l'admin CODflow</li>
 *   <li>{@link #CASTELLO_DIRECT} — postée par le site vitrine CASTELLO (Next.js)
 *       via l'endpoint public authentifié par API key</li>
 * </ul>
 *
 * <p>Stocké en base comme {@code VARCHAR(20)} via {@code @Enumerated(EnumType.STRING)}.
 * Toute nouvelle valeur doit tenir dans 20 caractères.</p>
 */
public enum OrderSource {
    SHOPIFY,
    EXCEL,
    MANUAL,
    CASTELLO_DIRECT
}
