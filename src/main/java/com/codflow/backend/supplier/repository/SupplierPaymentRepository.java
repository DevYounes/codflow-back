package com.codflow.backend.supplier.repository;

import com.codflow.backend.supplier.entity.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM SupplierPayment p WHERE p.supplierOrder.id = :orderId")
    BigDecimal sumAmountByOrderId(@Param("orderId") Long orderId);
}
