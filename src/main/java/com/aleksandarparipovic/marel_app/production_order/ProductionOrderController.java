package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/production-orders")
@RequiredArgsConstructor
public class ProductionOrderController {

    private final ProductionOrderService productionOrderService;

    @GetMapping("/active-production-orders")
    ResponseEntity<List<ProductionOrderOptionDto>> getAllActiveProductionOrders(){
        List<ProductionOrderOptionDto> productionOrderOptionDtos = productionOrderService.getAllActiveProductionOrders();
        return ResponseEntity.ok(productionOrderOptionDtos);
    }
}
