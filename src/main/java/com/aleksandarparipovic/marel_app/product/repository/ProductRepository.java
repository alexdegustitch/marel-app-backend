package com.aleksandarparipovic.marel_app.product.repository;


import com.aleksandarparipovic.marel_app.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository
        extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product>,
        ProductRepositoryCustom {

    List<Product> findByArchivedAtIsNullOrderByProductNameAsc();

    long countByArchivedAtIsNull();

    long countByArchivedAtIsNullAndActiveTrue();

    /** Live products that have not a single live operation attached. */
    @Query("""
            select count(p) from Product p
            where p.archivedAt is null
              and not exists (
                  select o.id from Operation o
                  where o.product = p and o.archivedAt is null
              )
            """)
    long countWithoutLiveOperations();

    /** Live operations on live products — the catalogue's operation total. */
    @Query("""
            select count(o) from Operation o
            where o.archivedAt is null and o.product.archivedAt is null
            """)
    long countLiveOperations();

    Optional<Product> findByIdAndArchivedAtIsNull(Long id);

    boolean existsByProductNameIgnoreCaseAndArchivedAtIsNull(String productName);

    boolean existsByProductCodeIgnoreCaseAndArchivedAtIsNull(String productCode);

}
