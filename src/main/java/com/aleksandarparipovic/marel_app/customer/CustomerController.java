package com.aleksandarparipovic.marel_app.customer;

import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOptionDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOrderRow;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerUpdateRequest;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The customer list.
 *
 * <p>No matcher of its own in {@code SecurityConfig}, which means it falls to
 * {@code anyRequest().authenticated()} — anybody signed in may read and maintain
 * it. That is deliberate rather than overlooked: whoever books an order needs to
 * be able to add the customer it is for, and a customer's name and tax id are
 * commercial details, not payroll or credentials.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /*
     * The customer's page lists the orders made for them. The orders are the
     * production order service's to answer for — this controller only decides
     * that the question is asked from the customer's address.
     */
    private final ProductionOrderService productionOrderService;

    @PostMapping
    public ResponseEntity<CustomerDto> create(@Valid @RequestBody CustomerCreateRequest request) {
        return ResponseEntity.ok(customerService.create(request));
    }

    /** One box searches name, code and tax id together — see CustomerSpecifications. */
    @GetMapping
    public ResponseEntity<Page<CustomerDto>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "name") String sortBy
    ) {
        return ResponseEntity.ok(
                customerService.search(query, active, page, size, direction, sortBy));
    }

    /** For pickers: the active customers only, by name. */
    @GetMapping("/options")
    public ResponseEntity<List<CustomerOptionDto>> options() {
        return ResponseEntity.ok(customerService.options());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.get(id));
    }

    /**
     * The production orders made for this customer.
     *
     * <p>Searched, sorted and paged BY THE SERVER. {@code query} is one free-text
     * box over the order's code, name and note and over its line items — their
     * notes and their products' names and codes. Every order that matches
     * anywhere comes back, with the matching line items marked so the page can
     * say WHERE the words were found.
     *
     * @param query     free text; blank or absent means no search
     * @param sortBy    {@code orderDate} (default) or {@code creationDate}
     * @param direction {@code DESC} (default) — newest first
     */
    @GetMapping("/{id}/production-orders")
    public ResponseEntity<Page<CustomerOrderRow>> productionOrders(
            @PathVariable Long id,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction,
            @RequestParam(defaultValue = "orderDate") String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return ResponseEntity.ok(
                productionOrderService.getCustomerOrders(id, query, direction, sortBy, page, size));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CustomerDto> update(
            @PathVariable Long id,
            @Valid @RequestBody CustomerUpdateRequest request
    ) {
        return ResponseEntity.ok(customerService.update(id, request));
    }

    /**
     * Deactivate. Not a delete — the orders that name this customer keep naming
     * them, and the row has to be there for that to mean anything.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        customerService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        customerService.restore(id);
        return ResponseEntity.noContent().build();
    }
}
