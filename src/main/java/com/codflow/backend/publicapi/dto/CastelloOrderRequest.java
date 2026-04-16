package com.codflow.backend.publicapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload attendu par l'endpoint public {@code POST /api/v1/public/orders/castello}
 * que le site vitrine CASTELLO (Next.js) utilise pour créer une commande dans CODflow.
 *
 * <p>Ce DTO est volontairement plus restrictif que {@link com.codflow.backend.order.dto.CreateOrderRequest} :
 * il reflète exactement ce qu'un visiteur peut saisir dans le formulaire public.</p>
 */
@Getter
@Setter
public class CastelloOrderRequest {

    /**
     * Référence externe unique côté CASTELLO (ex. {@code CAT-20260414ABCD}) — permet
     * à CASTELLO de suivre la commande et d'éviter les doublons en cas de retry réseau.
     */
    @NotBlank(message = "La référence externe (externalRef) est obligatoire")
    private String externalRef;

    @NotBlank(message = "Le nom du client est obligatoire")
    private String customerName;

    @NotBlank(message = "Le téléphone est obligatoire")
    @Pattern(
        regexp = "^(\\+?212|0)?\\s?[5-7]\\d{8}$",
        message = "Numéro de téléphone marocain invalide (ex. 0612345678, +212612345678)"
    )
    private String customerPhone;

    private String customerPhone2;

    @NotBlank(message = "L'adresse est obligatoire")
    private String address;

    @NotBlank(message = "La ville est obligatoire")
    private String ville;

    /** Identifiant Ozon de la ville (optionnel — résolu côté admin si absent). */
    private String deliveryCityId;

    private String notes;

    @NotEmpty(message = "La commande doit contenir au moins un article")
    @Valid
    private List<CastelloOrderItemRequest> items;

    @Getter
    @Setter
    public static class CastelloOrderItemRequest {

        /** SKU du produit CODflow (préféré quand CASTELLO lit le catalogue depuis CODflow). */
        private String productSku;

        /** ID produit CODflow (alternatif au SKU). */
        private Long productId;

        /** ID variant CODflow (pour gérer les pointures/couleurs). */
        private Long variantId;

        @NotBlank(message = "Le nom du produit est obligatoire")
        private String productName;

        @NotNull
        @Positive
        private Integer quantity = 1;

        @NotNull
        @Positive
        private BigDecimal unitPrice;
    }
}
