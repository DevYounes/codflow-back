package com.codflow.backend.delivery.repository;

import com.codflow.backend.delivery.entity.DeliveryProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryProviderRepository extends JpaRepository<DeliveryProviderConfig, Long> {

    Optional<DeliveryProviderConfig> findByCode(String code);

    Optional<DeliveryProviderConfig> findByCodeAndActiveTrue(String code);

    List<DeliveryProviderConfig> findByActiveTrue();
}
