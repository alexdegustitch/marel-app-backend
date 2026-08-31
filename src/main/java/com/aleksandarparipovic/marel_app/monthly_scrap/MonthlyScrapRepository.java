package com.aleksandarparipovic.marel_app.monthly_scrap;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MonthlyScrapRepository extends JpaRepository<MonthlyScrap, Long> {

    /**
     * One month's counted scrap, ordered the way the screen reads it.
     *
     * <p>Every association is fetched: the list shows the product, operation and
     * order name on each row, and four lazy proxies per row would be four extra
     * queries each.
     */
    @Query("""
            SELECT s FROM MonthlyScrap s
            LEFT JOIN FETCH s.product p
            LEFT JOIN FETCH s.operation o
            LEFT JOIN FETCH s.productionOrder po
            WHERE s.period = :period AND s.isActive = TRUE
            ORDER BY p.productName ASC, o.opName ASC, s.id ASC
            """)
    List<MonthlyScrap> findActiveForPeriod(@Param("period") LocalDate period);

    @Query("""
            SELECT s FROM MonthlyScrap s
            LEFT JOIN FETCH s.product
            LEFT JOIN FETCH s.operation
            LEFT JOIN FETCH s.productionOrder
            WHERE s.id = :id
            """)
    Optional<MonthlyScrap> findByIdWithRelations(@Param("id") Long id);
}
