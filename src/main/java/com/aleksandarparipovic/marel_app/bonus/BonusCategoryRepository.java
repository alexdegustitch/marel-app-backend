package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.department.Department;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BonusCategoryRepository
        extends JpaRepository<BonusCategory, Long>,
        JpaSpecificationExecutor<BonusCategory> {

    List<BonusCategory> findByActiveTrueOrderByCategoryNameAsc();

    @Query("""
        SELECT b FROM BonusCategory b
        WHERE b.active = true
          AND b.archivedAt IS NULL
          AND b.validFrom <= :today
          AND (b.validUntil IS NULL OR b.validUntil >= :today)
        ORDER BY b.bonusAmount ASC
        """)
    List<BonusCategory> findActiveAndValid(@Param("today") LocalDate today);

}
