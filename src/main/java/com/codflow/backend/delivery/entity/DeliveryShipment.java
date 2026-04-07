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

import java.math.BigDecimal;
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

    /** True quand le marchand a physiquement reçu le colis retourné. */
    @Column(name = "return_received", nullable = false)
    private boolean returnReceived = false;

    /** Date de confirmation de réception physique du retour. */
    @Column(name = "return_received_at")
    private LocalDateTime returnReceivedAt;

    /** Notes optionnelles lors de la confirmation de retour. */
    @Column(name = "return_received_notes", length = 500)
    private String returnReceivedNotes;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** DELIVERED-PRICE snapshot au moment de la création du colis */
    @Column(name = "delivered_price", precision = 10, scale = 2)
    private BigDecimal deliveredPrice;

    /** RETURNED-PRICE snapshot (souvent 0) */
    @Column(name = "returned_price", precision = 10, scale = 2)
    private BigDecimal returnedPrice;

    /** REFUSED-PRICE snapshot — client refuse à la porte */
    @Column(name = "refused_price", precision = 10, scale = 2)
    private BigDecimal refusedPrice;

    /** Frais réellement facturés selon le statut final */
    @Column(name = "applied_fee", precision = 10, scale = 2)
    private BigDecimal appliedFee;

    /** 'LIVRAISON', 'RETOUR', 'REFUS', ou 'ANNULATION' */
    @Column(name = "applied_fee_type", length = 20)
    private String appliedFeeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("eventAt DESC")
    private List<DeliveryTrackingHistory> trackingHistory = new ArrayList<>();
}
