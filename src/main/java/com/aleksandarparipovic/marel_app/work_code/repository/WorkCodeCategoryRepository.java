package com.aleksandarparipovic.marel_app.work_code.repository;

import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkCodeCategoryRepository extends JpaRepository<WorkCodeCategory, Long>, JpaSpecificationExecutor<WorkCodeCategory> {
    List<WorkCodeCategory> findByArchivedAtIsNullOrderByCategoryNo();
}
