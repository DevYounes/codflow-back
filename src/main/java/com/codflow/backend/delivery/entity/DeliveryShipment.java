package com.codflow.backend.delivery.entity;

import com.codflow.backend.common.entity.BaseEntity;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.order.entity.Order;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "delivery_shipments")
public class DeliveryShipment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private DeliveryProviderConfig provider;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "provider_order_id", length = 100)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ShipmentStatus status = ShipmentStatus.PENDING;

    @Column(name = "provider_status_label", length = 100)
    private String providerStatusLabel;

    @Column(name = "pickup_requested", nullable = false)
    private boolean pickupRequested = false;

    @Column(name = "pickup_requested_at")
    private LocalDateTime pickupRequestedAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "out_for_delivery_at")
    private LocalDateTime outForDeliveryAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("eventAt DESC")
    private List<DeliveryTrackingHistory> trackingHistory = new ArrayList<>();
}
