package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.production_order.dto.OrderCopySourceRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCardRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("api/production-orders")
@RequiredArgsConstructor
public class ProductionOrderController {

    private final ProductionOrderService productionOrderService;

    @PostMapping
    @PreAuthorize("hasAnyRole('commercial', 'admin', 'developer')")
    ResponseEntity<ProductionOrderDetailDto> create(@Valid @RequestBody ProductionOrderCreateRequest request) {
        return ResponseEntity.ok(productionOrderService.create(request));
    }

    @GetMapping("/active-production-orders")
    ResponseEntity<List<ProductionOrderOptionDto>> getAllActiveProductionOrders(){
        List<ProductionOrderOptionDto> productionOrderOptionDtos = productionOrderService.getAllActiveProductionOrders();
        return ResponseEntity.ok(productionOrderOptionDtos);
    }

    @PostMapping("/search-all")
    ResponseEntity<Page<ProductionOrderCardRow>> searchAll(@RequestBody SearchRequest request) {
        return ResponseEntity.ok(productionOrderService.searchAll(request));
    }

    /**
     * Past orders to copy line items out of — what "Kopiraj stavku" reads.
     *
     * <p>Searched, filtered, sorted and paged BY THE SERVER. {@code query} is one
     * box over everything an order is recognised by: its code, name and note, its
     * customer's name, code and tax id, and on its lines the product's name and
     * code, the description for the shop floor, the notes, and the quantity read
     * as text.
     *
     * <p>The three filters answer "which one was it" when the box cannot —
     * {@code userId} who wrote it, {@code customerId} who it was for, and
     * {@code createdFrom}/{@code createdTo} when it was created. The date span is
     * inclusive at both ends, so one day given twice means that day.
     *
     * <p>Written as a GET with plain parameters rather than through the shared
     * {@code SearchRequest}: this is a fixed question with five answers, and a
     * generic filter list would let a caller sort or filter by fields this screen
     * has no business reaching.
     */
    @GetMapping("/copy-sources")
    ResponseEntity<Page<OrderCopySourceRow>> copySources(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(productionOrderService.searchCopySources(
                query, customerId, userId, createdFrom, createdTo, page, size));
    }

    @GetMapping("/{id}")
    ResponseEntity<ProductionOrderDetailDto> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(productionOrderService.getDetail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('commercial', 'admin', 'developer')")
    ResponseEntity<ProductionOrderDetailDto> update(
            @PathVariable Long id, @Valid @RequestBody ProductionOrderUpdateRequest request
    ) {
        return ResponseEntity.ok(productionOrderService.update(id, request));
    }

    @PatchMapping("/{id}/deliver")
    @PreAuthorize("hasAnyRole('commercial', 'admin', 'developer')")
    ResponseEntity<ProductionOrderDetailDto> markDelivered(@PathVariable Long id) {
        return ResponseEntity.ok(productionOrderService.markDelivered(id));
    }
}
