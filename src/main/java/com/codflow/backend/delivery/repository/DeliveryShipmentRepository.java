package com.codflow.backend.delivery.repository;

import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryShipmentRepository extends JpaRepository<DeliveryShipment, Long> {

    Optional<DeliveryShipment> findByOrderId(Long orderId);

    Optional<DeliveryShipment> findByTrackingNumber(String trackingNumber);

    List<DeliveryShipment> findByStatus(ShipmentStatus status);

    Page<DeliveryShipment> findByStatus(ShipmentStatus status, Pageable pageable);

    @Query("SELECT s FROM DeliveryShipment s WHERE s.status NOT IN " +
           "('DELIVERED', 'RETURNED', 'CANCELLED') AND s.trackingNumber IS NOT NULL")
    List<DeliveryShipment> findActiveShipments();

    Page<DeliveryShipment> findByProviderId(Long providerId, Pageable pageable);

    long countByStatus(ShipmentStatus status);
}
