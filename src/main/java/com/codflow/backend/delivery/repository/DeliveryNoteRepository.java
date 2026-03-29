package com.codflow.backend.delivery.repository;

import com.codflow.backend.delivery.entity.DeliveryNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeliveryNoteRepository extends JpaRepository<DeliveryNote, Long> {

    Optional<DeliveryNote> findByRef(String ref);

    boolean existsByRef(String ref);

    Page<DeliveryNote> findByProviderId(Long providerId, Pageable pageable);

    @Query("SELECT n FROM DeliveryNote n WHERE n.provider.id = :providerId ORDER BY n.createdAt DESC")
    Page<DeliveryNote> findByProviderIdOrderByCreatedAtDesc(@Param("providerId") Long providerId, Pageable pageable);

    @Query("SELECT n FROM DeliveryNote n JOIN n.shipments s WHERE s.id = :shipmentId")
    Optional<DeliveryNote> findByShipmentId(@Param("shipmentId") Long shipmentId);
}
