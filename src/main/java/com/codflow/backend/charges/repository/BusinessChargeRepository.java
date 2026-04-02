package com.codflow.backend.charges.repository;

import com.codflow.backend.charges.entity.BusinessCharge;
import com.codflow.backend.charges.enums.BusinessChargeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface BusinessChargeRepository extends JpaRepository<BusinessCharge, Long>,
        JpaSpecificationExecutor<BusinessCharge> {

    List<BusinessCharge> findByChargeDateBetweenOrderByChargeDateDesc(LocalDate from, LocalDate to);

    List<BusinessCharge> findByTypeAndChargeDateBetweenOrderByChargeDateDesc(
            BusinessChargeType type, LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM BusinessCharge c WHERE c.chargeDate BETWEEN :from AND :to")
    BigDecimal sumByPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM BusinessCharge c WHERE c.type = :type AND c.chargeDate BETWEEN :from AND :to")
    BigDecimal sumByTypeAndPeriod(@Param("type") BusinessChargeType type,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);
}
