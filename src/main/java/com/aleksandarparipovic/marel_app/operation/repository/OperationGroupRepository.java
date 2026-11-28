package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.OperationGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperationGroupRepository extends JpaRepository<OperationGroup, Long> {

    List<OperationGroup> findByProduct_IdOrderByNameAsc(Long productId);

    @Query("""
        SELECT COUNT(g) > 0 FROM OperationGroup g
        WHERE g.product.id = :productId
          AND LOWER(g.name) = LOWER(:name)
          AND g.archivedAt IS NULL
          AND (:excludeId IS NULL OR g.id <> :excludeId)
        """)
    boolean nameTakenInProductByAnother(
            @Param("productId") Long productId,
            @Param("name") String name,
            @Param("excludeId") Long excludeId);
}
