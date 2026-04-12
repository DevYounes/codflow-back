package com.codflow.backend.supplier.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.supplier.dto.CreateSupplierRequest;
import com.codflow.backend.supplier.dto.SupplierDto;
import com.codflow.backend.supplier.entity.Supplier;
import com.codflow.backend.supplier.repository.SupplierOrderRepository;
import com.codflow.backend.supplier.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierOrderRepository supplierOrderRepository;

    @Transactional
    public SupplierDto createSupplier(CreateSupplierRequest request) {
        if (supplierRepository.existsByNameIgnoreCase(request.getName())) {
            throw new BusinessException("Un fournisseur avec ce nom existe déjà");
        }
        Supplier supplier = new Supplier();
        supplier.setName(request.getName());
        supplier.setPhone(request.getPhone());
        supplier.setEmail(request.getEmail());
        supplier.setAddress(request.getAddress());
        supplier.setNotes(request.getNotes());
        return toDto(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierDto updateSupplier(Long id, CreateSupplierRequest request) {
        Supplier supplier = getSupplierById(id);
        if (StringUtils.hasText(request.getName())) supplier.setName(request.getName());
        if (StringUtils.hasText(request.getPhone())) supplier.setPhone(request.getPhone());
        if (StringUtils.hasText(request.getEmail())) supplier.setEmail(request.getEmail());
        if (StringUtils.hasText(request.getAddress())) supplier.setAddress(request.getAddress());
        if (request.getNotes() != null) supplier.setNotes(request.getNotes());
        return toDto(supplierRepository.save(supplier));
    }

    @Transactional(readOnly = true)
    public SupplierDto getSupplier(Long id) {
        return toDto(getSupplierById(id));
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> getActiveSuppliers() {
        return supplierRepository.findByActiveTrueOrderByNameAsc()
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierDto> getSuppliers(Pageable pageable) {
        return PageResponse.of(supplierRepository.findAll(pageable).map(this::toDto));
    }

    @Transactional
    public void deactivateSupplier(Long id) {
        Supplier supplier = getSupplierById(id);
        supplier.setActive(false);
        supplierRepository.save(supplier);
    }

    public Supplier getSupplierById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fournisseur", id));
    }

    public SupplierDto toDto(Supplier supplier) {
        BigDecimal totalOrdered = supplierOrderRepository.sumTotalBySupplierId(supplier.getId());
        BigDecimal totalPaid = supplierOrderRepository.sumPaidBySupplierId(supplier.getId());
        return SupplierDto.builder()
                .id(supplier.getId())
                .name(supplier.getName())
                .phone(supplier.getPhone())
                .email(supplier.getEmail())
                .address(supplier.getAddress())
                .notes(supplier.getNotes())
                .active(supplier.isActive())
                .totalOrdered(totalOrdered)
                .totalPaid(totalPaid)
                .totalRemaining(totalOrdered.subtract(totalPaid))
                .createdAt(supplier.getCreatedAt())
                .build();
    }
}
