package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCardRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
