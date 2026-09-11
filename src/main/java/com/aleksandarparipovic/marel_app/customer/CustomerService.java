package com.aleksandarparipovic.marel_app.customer;

import com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.customer.CustomerInsightsQueryRepository.ProductionOrderCounts;
import com.aleksandarparipovic.marel_app.customer.CustomerInsightsQueryRepository.SampleOrderCounts;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDetailStatsDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerListRow;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOptionDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerStatsDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerTopProductRow;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerUpdateRequest;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * What the list may be sorted on, and what it sorts on when asked for
     * anything else. A whitelist rather than a pass-through — an unknown
     * property would reach the criteria builder as a field name and come back
     * as a stack trace instead of a list.
     */
    private static final Set<String> CUSTOMER_SORT_FIELDS = Set.of("name", "code", "createdAt");
    private static final String DEFAULT_SORT_FIELD = "name";
    private static final int MAX_PAGE_SIZE = 200;
    private static final int TOP_PRODUCTS_LIMIT = 8;

    private final CustomerRepository customerRepository;
    private final CustomerInsightsQueryRepository insights;
    private final CustomerMapper mapper;
    private final PasswordConfirmationService passwordConfirmation;

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

    /**
     * The list page's slice: searched, filtered, sorted and paged BY THE
     * SERVER, each row carrying the order counts the card prints. Counting is
     * two grouped queries for the whole page, never one per customer.
     *
     * @param hasActiveOrders true narrows to customers with an open production
     *                        order (the KPI tile's filter); null means no filter
     */
    @Transactional(readOnly = true)
    public Page<CustomerListRow> search(
            String query,
            Boolean active,
            Boolean hasActiveOrders,
            int page,
            int size,
            Sort.Direction direction,
            String sortBy
    ) {
        String sortField = CUSTOMER_SORT_FIELDS.contains(sortBy) ? sortBy : DEFAULT_SORT_FIELD;
        Sort.Direction sortDirection = direction == null ? Sort.Direction.ASC : direction;

        // The id last, so customers tied on the sorted field keep a fixed
        // position instead of moving between pages.
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by(
                        new Sort.Order(sortDirection, sortField).nullsLast(),
                        new Sort.Order(Sort.Direction.ASC, "id")));

        Specification<Customer> spec = Specification.allOf();
        if (query != null && !query.isBlank()) {
            spec = spec.and(CustomerSpecifications.matches(query));
        }
        if (active != null) {
            spec = spec.and(CustomerSpecifications.isActive(active));
        }
        if (Boolean.TRUE.equals(hasActiveOrders)) {
            spec = spec.and(CustomerSpecifications.hasActiveProductionOrders());
        }

        Page<Customer> customers = customerRepository.findAll(spec, pageable);
        return customers.map(rowsFor(customers.getContent()));
    }

    /** The per-customer counts for one page of customers, fetched up front. */
    private Function<Customer, CustomerListRow> rowsFor(List<Customer> pageContent) {
        List<Long> ids = pageContent.stream().map(Customer::getId).toList();

        Map<Long, ProductionOrderCounts> orders = ids.isEmpty() ? Map.of()
                : insights.productionOrderCounts(ids, ProductionOrderStatus.CREATED)
                        .stream()
                        .collect(Collectors.toMap(ProductionOrderCounts::getCustomerId, row -> row));
        Map<Long, SampleOrderCounts> samples = ids.isEmpty() ? Map.of()
                : insights.sampleOrderCounts(ids)
                        .stream()
                        .collect(Collectors.toMap(SampleOrderCounts::getCustomerId, row -> row));

        return customer -> {
            ProductionOrderCounts order = orders.get(customer.getId());
            SampleOrderCounts sample = samples.get(customer.getId());
            return new CustomerListRow(
                    customer.getId(),
                    customer.getCode(),
                    customer.getName(),
                    customer.getTaxId(),
                    customer.getWebsite(),
                    customer.getEmail(),
                    customer.getPhone(),
                    customer.getIsActive(),
                    customer.getArchivedAt(),
                    order == null ? 0 : order.getTotal(),
                    order == null ? 0 : order.getActive(),
                    sample == null ? 0 : sample.getTotal(),
                    order == null ? null : order.getLastOrderDate());
        };
    }

    /** The board's four figures — whole-population counts, never filter-scoped. */
    @Transactional(readOnly = true)
    public CustomerStatsDto stats() {
        long total = customerRepository.count();
        long active = customerRepository.count(CustomerSpecifications.isActive(true));
        return new CustomerStatsDto(
                total,
                active,
                total - active,
                insights.countCustomersWithActiveOrders(ProductionOrderStatus.CREATED));
    }

    /** One customer's order figures, for the KPI row on their page. */
    @Transactional(readOnly = true)
    public CustomerDetailStatsDto detailStats(Long id) {
        requireExists(id);

        Map<Long, ProductionOrderCounts> orders = insights
                .productionOrderCounts(List.of(id), ProductionOrderStatus.CREATED)
                .stream()
                .collect(Collectors.toMap(ProductionOrderCounts::getCustomerId, row -> row));
        Map<Long, SampleOrderCounts> samples = insights.sampleOrderCounts(List.of(id))
                .stream()
                .collect(Collectors.toMap(SampleOrderCounts::getCustomerId, row -> row));

        ProductionOrderCounts order = orders.get(id);
        SampleOrderCounts sample = samples.get(id);
        long orderTotal = order == null ? 0 : order.getTotal();
        long orderActive = order == null ? 0 : order.getActive();
        return new CustomerDetailStatsDto(
                orderTotal,
                orderActive,
                orderTotal - orderActive,
                sample == null ? 0 : sample.getTotal(),
                sample == null ? 0 : sample.getOpen());
    }

    /** What this customer orders most, a handful of lines for the panel. */
    @Transactional(readOnly = true)
    public List<CustomerTopProductRow> topProducts(Long id) {
        requireExists(id);
        return insights.topProducts(id, PageRequest.of(0, TOP_PRODUCTS_LIMIT));
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

    /**
     * Deactivate, signed with the caller's re-typed password — the same
     * signature the catalogue archives ask for. Wrong password answers
     * {@code WRONG_PASSWORD} before anything is touched.
     */
    @Transactional
    public void archive(Long id, String password, Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);
        deactivate(id);
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

    private void requireExists(Long id) {
        if (!customerRepository.existsById(id)) {
            throw new EntityNotFoundException("Kupac nije pronađen: " + id);
        }
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
