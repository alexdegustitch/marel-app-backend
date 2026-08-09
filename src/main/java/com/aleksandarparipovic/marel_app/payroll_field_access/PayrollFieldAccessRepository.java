package com.aleksandarparipovic.marel_app.payroll_field_access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollFieldAccessRepository extends JpaRepository<PayrollFieldAccess, Long> {

    @Query("select a from PayrollFieldAccess a where lower(a.roleName) = lower(:roleName)")
    List<PayrollFieldAccess> findForRole(@Param("roleName") String roleName);

    @Query("""
           select a from PayrollFieldAccess a
           where lower(a.roleName) = lower(:roleName) and a.fieldCode = :fieldCode
           """)
    Optional<PayrollFieldAccess> findOne(@Param("roleName") String roleName,
                                         @Param("fieldCode") String fieldCode);
}
