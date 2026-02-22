package com.aleksandarparipovic.marel_app.product.repository;


import com.aleksandarparipovic.marel_app.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository
        extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product>,
        ProductRepositoryCustom {

    List<Product> findByArchivedAtIsNullOrderByProductNameAsc();

}
