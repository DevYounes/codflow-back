package com.codflow.backend.customer.service;

import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.customer.dto.CustomerDto;
import com.codflow.backend.customer.dto.UpdateCustomerRequest;
import com.codflow.backend.customer.entity.Customer;
import com.codflow.backend.customer.enums.CustomerStatus;
import com.codflow.backend.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    // Auto-tag as FIDELE when confirmed orders reach this threshold
    private static final int FIDELE_THRESHOLD = 3;

    private final CustomerRepository customerRepository;

    /**
     * Returns or creates a customer by normalized phone.
     * Called from OrderService when a new order is created.
     */
    @Transactional
    public Customer findOrCreate(String phone, String phoneNormalized,
                                 String fullName, String address, String ville) {
        String lookupKey = phoneNormalized != null ? phoneNormalized : phone;

        if (phoneNormalized != null) {
            return customerRepository.findByPhoneNormalized(phoneNormalized)
                    .orElseGet(() -> create(phone, phoneNormalized, fullName, address, ville));
        }
        return customerRepository.findByPhone(phone)
                .orElseGet(() -> create(phone, null, fullName, address, ville));
    }

    @Transactional(readOnly = true)
    public Page<CustomerDto> getCustomers(CustomerStatus status, String search, Pageable pageable) {
        String searchTerm = (search != null && !search.isBlank()) ? search.trim() : null;
        return customerRepository.findWithFilters(status, searchTerm, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public CustomerDto getCustomer(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));
        return toDto(customer);
    }

    @Transactional
    public CustomerDto update(Long id, UpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));

        if (request.getFullName() != null && !request.getFullName().isBlank())
            customer.setFullName(request.getFullName());
        if (request.getEmail() != null)
            customer.setEmail(request.getEmail());
        if (request.getAddress() != null)
            customer.setAddress(request.getAddress());
        if (request.getVille() != null)
            customer.setVille(request.getVille());
        if (request.getStatus() != null)
            customer.setStatus(request.getStatus());
        if (request.getNotes() != null)
            customer.setNotes(request.getNotes());

        return toDto(customerRepository.save(customer));
    }

    /**
     * Convenience endpoint: change only the status + optional note.
     */
    @Transactional
    public CustomerDto updateStatus(Long id, CustomerStatus status, String notes) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));
        customer.setStatus(status);
        if (notes != null) customer.setNotes(notes);
        return toDto(customerRepository.save(customer));
    }

    /**
     * After each confirmed order, re-evaluate if customer should be tagged FIDELE.
     * Only promotes ACTIVE → FIDELE, never demotes.
     */
    @Transactional
    public void refreshFideleStatus(Long customerId) {
        customerRepository.findById(customerId).ifPresent(customer -> {
            if (customer.getStatus() != CustomerStatus.ACTIVE) return;
            long confirmed = customerRepository.countConfirmedOrdersByCustomer(customerId);
            if (confirmed >= FIDELE_THRESHOLD) {
                customer.setStatus(CustomerStatus.FIDELE);
                customerRepository.save(customer);
                log.info("Customer {} tagged as FIDELE after {} confirmed orders", customerId, confirmed);
            }
        });
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private CustomerDto toDto(Customer c) {
        long total     = customerRepository.countOrdersByCustomer(c.getId());
        long confirmed = customerRepository.countConfirmedOrdersByCustomer(c.getId());
        long cancelled = customerRepository.countCancelledOrdersByCustomer(c.getId());
        double rate = total > 0
                ? BigDecimal.valueOf((double) confirmed / total * 100)
                             .setScale(2, RoundingMode.HALF_UP).doubleValue()
                : 0;

        return CustomerDto.builder()
                .id(c.getId())
                .fullName(c.getFullName())
                .phone(c.getPhone())
                .email(c.getEmail())
                .address(c.getAddress())
                .ville(c.getVille())
                .status(c.getStatus())
                .statusLabel(c.getStatus().getLabel())
                .notes(c.getNotes())
                .createdAt(c.getCreatedAt())
                .totalOrders(total)
                .confirmedOrders(confirmed)
                .cancelledOrders(cancelled)
                .confirmationRate(rate)
                .lastOrderDate(customerRepository.lastOrderDateByCustomer(c.getId()))
                .build();
    }

    private Customer create(String phone, String phoneNormalized,
                            String fullName, String address, String ville) {
        Customer c = new Customer();
        c.setPhone(phone);
        c.setPhoneNormalized(phoneNormalized);
        c.setFullName(fullName != null ? fullName : "Client inconnu");
        c.setAddress(address);
        c.setVille(ville);
        c.setStatus(CustomerStatus.ACTIVE);
        return customerRepository.save(c);
    }
}
