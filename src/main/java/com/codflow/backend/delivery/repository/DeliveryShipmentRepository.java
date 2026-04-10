package com.codflow.backend.delivery.repository;

import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryShipmentRepository extends JpaRepository<DeliveryShipment, Long>,
        JpaSpecificationExecutor<DeliveryShipment> {

    Optional<DeliveryShipment> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    Optional<DeliveryShipment> findByTrackingNumber(String trackingNumber);

    List<DeliveryShipment> findByStatus(ShipmentStatus status);

    // CANCELLED est un état transitoire chez Ozon ("Annulé" = tentative annulée, pas le colis définitivement).
    // Le colis peut encore être redistribué ou retourné → on continue à le synchroniser.
    @Query("SELECT s FROM DeliveryShipment s WHERE s.status NOT IN " +
           "('DELIVERED', 'RETURNED') AND s.trackingNumber IS NOT NULL")
    List<DeliveryShipment> findActiveShipments();

    /**
     * Colis en attente de retour physique :
     * - statut FAILED_DELIVERY (refusé/échec) : le transporteur doit ramener le colis
     * - statut RETURNED : le transporteur dit qu'il a retourné, mais pas encore confirmé reçu par le marchand
     * - statut CANCELLED : annulé après expédition, retour attendu
     * Tous filtrés sur return_received = false.
     */
    @Query("SELECT s FROM DeliveryShipment s WHERE s.status IN " +
           "('FAILED_DELIVERY', 'RETURNED', 'CANCELLED') AND s.returnReceived = false " +
           "ORDER BY s.updatedAt ASC")
    List<DeliveryShipment> findPendingReturns();

    Page<DeliveryShipment> findByProviderId(Long providerId, Pageable pageable);

    long countByStatus(ShipmentStatus status);

    /**
     * Somme de toutes les tentatives d'annulation de livraison pour un client donné.
     * Traverse : DeliveryShipment → Order → Customer.
     */
    @Query("SELECT COALESCE(SUM(s.cancelledAttempts), 0) FROM DeliveryShipment s WHERE s.order.customer.id = :customerId")
    long sumCancelledAttemptsByCustomerId(@Param("customerId") Long customerId);
}
