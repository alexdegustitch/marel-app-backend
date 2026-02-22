package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public ProductOptionDto toDtoOption(Product p){
        return new ProductOptionDto(p.getId(), p.getProductName());
    }
}
