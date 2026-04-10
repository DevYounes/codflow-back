package com.codflow.backend.supplier.repository;

import com.codflow.backend.supplier.entity.SupplierOrder;
import com.codflow.backend.supplier.enums.SupplierOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface SupplierOrderRepository extends JpaRepository<SupplierOrder, Long> {

    boolean existsByOrderNumber(String orderNumber);

    @Query("SELECT o FROM SupplierOrder o JOIN FETCH o.supplier WHERE o.id = :id")
    Optional<SupplierOrder> findByIdWithSupplier(@Param("id") Long id);

    Page<SupplierOrder> findBySupplierId(Long supplierId, Pageable pageable);

    Page<SupplierOrder> findByStatus(SupplierOrderStatus status, Pageable pageable);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM SupplierOrder o WHERE o.supplier.id = :supplierId AND o.status != 'ANNULE'")
    BigDecimal sumTotalBySupplierId(@Param("supplierId") Long supplierId);

    @Query("SELECT COALESCE(SUM(o.paidAmount), 0) FROM SupplierOrder o WHERE o.supplier.id = :supplierId AND o.status != 'ANNULE'")
    BigDecimal sumPaidBySupplierId(@Param("supplierId") Long supplierId);
}
