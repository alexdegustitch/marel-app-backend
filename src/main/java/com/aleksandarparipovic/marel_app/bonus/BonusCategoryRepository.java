package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.department.Department;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BonusCategoryRepository
        extends JpaRepository<BonusCategory, Long>,
        JpaSpecificationExecutor<BonusCategory> {

    List<BonusCategory> findByActiveTrueOrderByCategoryNameAsc();

}
