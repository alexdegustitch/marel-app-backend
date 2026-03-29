package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import org.springframework.stereotype.Component;

@Component
public class ProductionOrderMapper {

    ProductionOrderOptionDto toOptionDto(ProductionOrder productionOrder){
        return new ProductionOrderOptionDto(productionOrder.getId(), productionOrder.getCode(), productionOrder.getName());
    }
}
