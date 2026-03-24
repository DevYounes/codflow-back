package com.codflow.backend.delivery.entity;

import com.codflow.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "delivery_providers")
public class DeliveryProviderConfig extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(name = "api_base_url", length = 500)
    private String apiBaseUrl;

    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "api_token", length = 500)
    private String apiToken;

    @Column(nullable = false)
    private boolean active = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String config;
}
