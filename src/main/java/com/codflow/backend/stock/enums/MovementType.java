package com.codflow.backend.stock.enums;

public enum MovementType {
    IN,              // Entrée de stock (achat, arrivage)
    OUT,             // Sortie définitive (commande livrée)
    ADJUSTMENT,      // Ajustement manuel
    RETURN,          // Retour client (réintégration physique)
    RESERVE,         // Réservation (commande confirmée, pas encore livrée)
    RESERVE_RELEASE  // Libération de réservation (commande annulée ou retournée avant livraison)
}
