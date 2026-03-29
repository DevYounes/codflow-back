package com.codflow.backend.delivery.entity;

import com.codflow.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "delivery_notes")
public class DeliveryNote extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private DeliveryProviderConfig provider;

    /** DRAFT = created but not yet saved; SAVED = finalized and PDF available */
    @Column(nullable = false, length = 30)
    private String status = "SAVED";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "delivery_note_shipments",
        joinColumns = @JoinColumn(name = "delivery_note_id"),
        inverseJoinColumns = @JoinColumn(name = "shipment_id")
    )
    private List<DeliveryShipment> shipments = new ArrayList<>();
}
