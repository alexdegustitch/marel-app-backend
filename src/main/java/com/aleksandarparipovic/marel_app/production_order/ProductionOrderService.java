package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductionOrderService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderMapper productionOrderMapper;

    List<ProductionOrderOptionDto> getAllActiveProductionOrders(){
        return productionOrderRepository.findByIsActiveIsTrueOrderByNameAsc()
                .stream()
                .map(productionOrderMapper::toOptionDto)
                .toList();
    }
}
