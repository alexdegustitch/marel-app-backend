package com.aleksandarparipovic.marel_app.product_attribute_value;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, Long> {

    /** Every value a product has recorded, whatever attribute it belongs to. */
    List<ProductAttributeValue> findByProduct_Id(Long productId);
}
