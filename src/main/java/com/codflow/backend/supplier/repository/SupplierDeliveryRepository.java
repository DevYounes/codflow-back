package com.codflow.backend.supplier.repository;

import com.codflow.backend.supplier.entity.SupplierDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierDeliveryRepository extends JpaRepository<SupplierDelivery, Long> {

    List<SupplierDelivery> findBySupplierOrderIdOrderByDeliveryDateAsc(Long supplierOrderId);

    @Query("SELECT d FROM SupplierDelivery d JOIN FETCH d.items i JOIN FETCH i.orderItem WHERE d.id = :id")
    Optional<SupplierDelivery> findByIdWithItems(@Param("id") Long id);
}
