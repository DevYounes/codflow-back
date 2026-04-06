package com.codflow.backend.delivery.service;

import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.delivery.dto.CreateDeliveryNoteRequest;
import com.codflow.backend.delivery.dto.DeliveryNoteDto;
import com.codflow.backend.delivery.entity.DeliveryNote;
import com.codflow.backend.delivery.entity.DeliveryProviderConfig;
import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.provider.DeliveryProviderAdapter.ProviderConfig;
import com.codflow.backend.delivery.provider.ozon.OzonExpressAdapter;
import com.codflow.backend.delivery.repository.DeliveryNoteRepository;
import com.codflow.backend.delivery.repository.DeliveryProviderRepository;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryNoteService {

    private final DeliveryNoteRepository noteRepository;
    private final DeliveryProviderRepository providerRepository;
    private final DeliveryShipmentRepository shipmentRepository;
    private final OzonExpressAdapter ozonAdapter;

    /**
     * Full 3-step flow: create BL → add parcels → save → return DTO with PDF links.
     */
    @Transactional
    public DeliveryNoteDto createNote(CreateDeliveryNoteRequest request) {
        DeliveryProviderConfig provider = providerRepository.findById(request.getProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("Transporteur", request.getProviderId()));

        List<DeliveryShipment> shipments = shipmentRepository.findAllById(request.getShipmentIds());
        if (shipments.isEmpty()) {
            throw new BusinessException("Aucun envoi trouvé pour les IDs fournis");
        }

        List<String> trackingNumbers = shipments.stream()
                .filter(s -> s.getTrackingNumber() != null)
                .map(DeliveryShipment::getTrackingNumber)
                .collect(Collectors.toList());

        if (trackingNumbers.isEmpty()) {
            throw new BusinessException("Aucun des envois sélectionnés n'a de numéro de tracking");
        }

        ProviderConfig adapterConfig = new ProviderConfig(
                provider.getApiBaseUrl(),
                provider.getApiKey(),
                provider.getApiToken(),
                provider.getConfig()
        );

        // Step 1 — Create BL
        String ref = ozonAdapter.createDeliveryNote(adapterConfig);

        // Step 2 — Add parcels
        ozonAdapter.addParcelsToDeliveryNote(ref, trackingNumbers, adapterConfig);

        // Step 3 — Save BL
        ozonAdapter.saveDeliveryNote(ref, adapterConfig);

        // Persist locally
        DeliveryNote note = new DeliveryNote();
        note.setRef(ref);
        note.setProvider(provider);
        note.setStatus("SAVED");
        note.setNotes(request.getNotes());
        note.setShipments(shipments);

        return toDto(noteRepository.save(note));
    }

    @Transactional(readOnly = true)
    public Page<DeliveryNoteDto> listNotes(Pageable pageable) {
        return noteRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public DeliveryNoteDto getNote(Long id) {
        return toDto(noteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bon de livraison", id)));
    }

    @Transactional(readOnly = true)
    public DeliveryNoteDto getNoteByRef(String ref) {
        return toDto(noteRepository.findByRef(ref)
                .orElseThrow(() -> new ResourceNotFoundException("Bon de livraison", Long.parseLong(ref))));
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private DeliveryNoteDto toDto(DeliveryNote note) {
        OzonExpressAdapter.DeliveryNotePdfs pdfs = ozonAdapter.getPdfUrls(note.getRef());

        List<DeliveryNoteDto.ShipmentSummary> shipmentSummaries = note.getShipments().stream()
                .map(s -> DeliveryNoteDto.ShipmentSummary.builder()
                        .shipmentId(s.getId())
                        .orderId(s.getOrder().getId())
                        .orderNumber(s.getOrder().getOrderNumber())
                        .customerName(s.getOrder().getCustomerName())
                        .trackingNumber(s.getTrackingNumber())
                        .status(s.getStatus().name())
                        .build())
                .collect(Collectors.toList());

        return DeliveryNoteDto.builder()
                .id(note.getId())
                .ref(note.getRef())
                .providerId(note.getProvider().getId())
                .providerName(note.getProvider().getName())
                .status(note.getStatus())
                .notes(note.getNotes())
                .shipmentCount(note.getShipments().size())
                .shipments(shipmentSummaries)
                .pdfUrl(pdfs.standard())
                .pdfTicketsA4Url(pdfs.ticketsA4())
                .pdfTickets10x10Url(pdfs.tickets10x10())
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .build();
    }
}
