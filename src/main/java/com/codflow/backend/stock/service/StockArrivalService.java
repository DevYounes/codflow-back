package com.codflow.backend.stock.service;

import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.stock.dto.CreateStockArrivalRequest;
import com.codflow.backend.stock.dto.StockArrivalDto;
import com.codflow.backend.stock.entity.StockArrival;
import com.codflow.backend.stock.entity.StockArrivalItem;
import com.codflow.backend.stock.entity.StockMovement;
import com.codflow.backend.stock.enums.MovementType;
import com.codflow.backend.stock.repository.StockArrivalRepository;
import com.codflow.backend.stock.repository.StockMovementRepository;
import com.codflow.backend.team.repository.UserRepository;
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
public class StockArrivalService {

    private final StockArrivalRepository arrivalRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;

    @Transactional
    public StockArrivalDto createArrival(CreateStockArrivalRequest request, UserPrincipal principal) {
        if (arrivalRepository.existsByReference(request.getReference())) {
            throw new BusinessException("Un arrivage avec cette référence existe déjà: " + request.getReference());
        }

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Produit", request.getProductId()));

        StockArrival arrival = new StockArrival();
        arrival.setReference(request.getReference());
        arrival.setProduct(product);
        arrival.setArrivedAt(request.getArrivedAt());
        arrival.setNotes(request.getNotes());

        if (principal != null) {
            userRepository.findById(principal.getId()).ifPresent(arrival::setCreatedBy);
        }

        for (CreateStockArrivalRequest.ArrivalItemRequest itemReq : request.getItems()) {
            if (itemReq.getQuantity() == null || itemReq.getQuantity() < 0) {
                throw new BusinessException("La quantité doit être >= 0");
            }

            StockArrivalItem item = new StockArrivalItem();
            item.setArrival(arrival);
            item.setQuantity(itemReq.getQuantity());
            item.setUnitCost(itemReq.getUnitCost());

            if (itemReq.getVariantId() != null) {
                ProductVariant variant = variantRepository.findById(itemReq.getVariantId())
                        .orElseThrow(() -> new ResourceNotFoundException("Variante", itemReq.getVariantId()));
                if (!variant.getProduct().getId().equals(product.getId())) {
                    throw new BusinessException("La variante " + itemReq.getVariantId() + " n'appartient pas à ce produit");
                }
                item.setVariant(variant);

                // Mise à jour du stock variante
                if (itemReq.getQuantity() > 0) {
                    variant.setCurrentStock(variant.getCurrentStock() + itemReq.getQuantity());
                    variantRepository.save(variant);
                }
            }

            arrival.getItems().add(item);
        }

        // Mise à jour du stock total produit
        int totalQty = request.getItems().stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();
        if (totalQty > 0) {
            int previousStock = product.getCurrentStock();
            product.setCurrentStock(previousStock + totalQty);
            productRepository.save(product);

            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(MovementType.IN);
            movement.setQuantity(totalQty);
            movement.setPreviousStock(previousStock);
            movement.setNewStock(product.getCurrentStock());
            movement.setReason("Arrivage " + request.getReference());
            movement.setReferenceType("ARRIVAL");
            stockMovementRepository.save(movement);
        }

        return toDto(arrivalRepository.save(arrival));
    }

    @Transactional(readOnly = true)
    public Page<StockArrivalDto> listArrivals(Pageable pageable) {
        return arrivalRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<StockArrivalDto> listByProduct(Long productId) {
        return arrivalRepository.findByProductIdOrderByArrivedAtDesc(productId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public StockArrivalDto getArrival(Long id) {
        return toDto(arrivalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Arrivage", id)));
    }

    // -------------------------------------------------------------------------

    private StockArrivalDto toDto(StockArrival a) {
        List<StockArrivalDto.ArrivalItemDto> items = a.getItems().stream().map(item -> {
            String label = null;
            if (item.getVariant() != null) {
                ProductVariant v = item.getVariant();
                label = buildVariantLabel(v);
            }
            return StockArrivalDto.ArrivalItemDto.builder()
                    .id(item.getId())
                    .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                    .variantLabel(label)
                    .quantity(item.getQuantity())
                    .unitCost(item.getUnitCost())
                    .build();
        }).collect(Collectors.toList());

        int total = items.stream().mapToInt(StockArrivalDto.ArrivalItemDto::getQuantity).sum();

        return StockArrivalDto.builder()
                .id(a.getId())
                .reference(a.getReference())
                .productId(a.getProduct().getId())
                .productName(a.getProduct().getName())
                .productSku(a.getProduct().getSku())
                .arrivedAt(a.getArrivedAt())
                .notes(a.getNotes())
                .items(items)
                .totalQuantity(total)
                .createdAt(a.getCreatedAt())
                .build();
    }

    private String buildVariantLabel(ProductVariant v) {
        StringBuilder sb = new StringBuilder();
        if (v.getSize() != null)  sb.append("T.").append(v.getSize());
        if (v.getColor() != null) { if (!sb.isEmpty()) sb.append(" / "); sb.append(v.getColor()); }
        return sb.isEmpty() ? (v.getVariantSku() != null ? v.getVariantSku() : "Variante " + v.getId()) : sb.toString();
    }
}
