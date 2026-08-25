package com.aleksandarparipovic.marel_app.customer;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOptionDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerUpdateRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Looking after the list of customers.
 *
 * <p><b>Blank is not a value.</b> Every optional field is normalised to null
 * when it arrives empty, because the unique indexes on `code` and `tax_id` are
 * partial — they ignore NULL and would happily collide on a run of empty
 * strings, so two customers with "no code" would be two customers with the same
 * code.
 *
 * <p><b>Nothing is deleted.</b> Orders reference the customer they were made
 * for. Deactivating leaves that history intact and stops the customer being
 * offered for new work, which is the whole of what "remove" can honestly mean
 * here.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper mapper;

    @Transactional
    public CustomerDto create(CustomerCreateRequest request) {
        String code = blankToNull(request.getCode());
        String taxId = blankToNull(request.getTaxId());

        requireCodeFree(code, null);
        requireTaxIdFree(taxId, null);

        Customer customer = Customer.builder()
                .name(request.getName().trim())
                .code(code)
                .taxId(taxId)
                .website(blankToNull(request.getWebsite()))
                .email(blankToNull(request.getEmail()))
                .phone(blankToNull(request.getPhone()))
                .isActive(true)
                .build();

        return mapper.toDto(customerRepository.save(customer));
    }

    @Transactional(readOnly = true)
    public Page<CustomerDto> search(
            String query,
            Boolean active,
            int page,
            int size,
            Sort.Direction direction,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Specification<Customer> spec = Specification.allOf();
        if (query != null && !query.isBlank()) {
            spec = spec.and(CustomerSpecifications.matches(query));
        }
        if (active != null) {
            spec = spec.and(CustomerSpecifications.isActive(active));
        }

        return customerRepository.findAll(spec, pageable).map(mapper::toDto);
    }

    /**
     * What a picker offers: the active ones, by name.
     *
     * <p>Deactivated customers are left out on purpose — they are not somebody
     * new work should be booked against. An order that already names one keeps
     * naming it; this list is about what may be CHOSEN, not what exists.
     */
    @Transactional(readOnly = true)
    public List<CustomerOptionDto> options() {
        return customerRepository.findByIsActiveTrueOrderByNameAsc()
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerDto get(Long id) {
        return mapper.toDto(load(id));
    }

    @Transactional
    public CustomerDto update(Long id, CustomerUpdateRequest request) {
        Customer customer = load(id);

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Naziv kupca je obavezan.");
            }
            customer.setName(name);
        }

        /*
         * Null leaves the field alone; a blank string clears it. Both go through
         * blankToNull, so "   " and "" reach the column as NULL rather than as a
         * value the partial unique index would let a second customer repeat.
         */
        if (request.getCode() != null) {
            String code = blankToNull(request.getCode());
            requireCodeFree(code, id);
            customer.setCode(code);
        }

        if (request.getTaxId() != null) {
            String taxId = blankToNull(request.getTaxId());
            requireTaxIdFree(taxId, id);
            customer.setTaxId(taxId);
        }

        if (request.getWebsite() != null) {
            customer.setWebsite(blankToNull(request.getWebsite()));
        }
        if (request.getEmail() != null) {
            customer.setEmail(blankToNull(request.getEmail()));
        }
        if (request.getPhone() != null) {
            customer.setPhone(blankToNull(request.getPhone()));
        }

        // archived_at follows on its own — the triggers set it on deactivation
        // and clear it on the way back.
        if (request.getActive() != null) {
            customer.setIsActive(request.getActive());
        }

        return mapper.toDto(customerRepository.save(customer));
    }

    /** Deactivate. The customer stays, and so does every order that names them. */
    @Transactional
    public void deactivate(Long id) {
        Customer customer = load(id);
        customer.setIsActive(false);
        customerRepository.save(customer);
    }

    @Transactional
    public void restore(Long id) {
        Customer customer = load(id);
        customer.setIsActive(true);
        customerRepository.save(customer);
    }

    private Customer load(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Kupac nije pronađen: " + id));
    }

    private void requireCodeFree(String code, Long excludeId) {
        if (code != null && customerRepository.codeTakenByAnother(code, excludeId)) {
            throw new ConflictException("Ta šifra se već koristi za drugog kupca.");
        }
    }

    private void requireTaxIdFree(String taxId, Long excludeId) {
        if (taxId != null && customerRepository.taxIdTakenByAnother(taxId, excludeId)) {
            throw new ConflictException("Taj PIB se već koristi za drugog kupca.");
        }
    }

    /**
     * An empty box means "nothing here", not "the empty string".
     *
     * <p>It matters more than it looks: `uq_customers_code_ci` is partial and
     * skips NULL, so a blank stored as "" would be a value — and the second
     * customer without a code would be refused for colliding with the first.
     */
    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
