package com.codflow.backend.customer.repository;

import com.codflow.backend.customer.entity.Customer;
import com.codflow.backend.customer.enums.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByPhoneNormalized(String phoneNormalized);

    Optional<Customer> findByPhone(String phone);

    boolean existsByPhoneNormalized(String phoneNormalized);

    @Query("""
            SELECT c FROM Customer c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL OR LOWER(c.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR c.phone LIKE CONCAT('%', :search, '%')
                   OR LOWER(c.ville) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Customer> findWithFilters(
            @Param("status") CustomerStatus status,
            @Param("search") String search,
            Pageable pageable);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId")
    long countOrdersByCustomer(@Param("customerId") Long customerId);

    @Query("""
            SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId
            AND o.status IN ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON','LIVRE','ECHEC_LIVRAISON','RETOURNE')
            """)
    long countConfirmedOrdersByCustomer(@Param("customerId") Long customerId);

    @Query("""
            SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId
            AND o.status IN ('ANNULE','PAS_SERIEUX','FAKE_ORDER')
            """)
    long countCancelledOrdersByCustomer(@Param("customerId") Long customerId);

    @Query("SELECT MAX(o.createdAt) FROM Order o WHERE o.customer.id = :customerId")
    java.time.LocalDateTime lastOrderDateByCustomer(@Param("customerId") Long customerId);
}
