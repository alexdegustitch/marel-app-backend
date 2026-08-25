package com.aleksandarparipovic.marel_app.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    List<Customer> findByIsActiveTrueOrderByNameAsc();

    /*
     * Both of these ask "does anybody ELSE have this", which is why each takes an
     * id to exclude. Written as one query rather than as a lookup followed by a
     * comparison in the service: saving a customer without changing its code
     * would otherwise find itself and refuse.
     *
     * The database has the last word either way — uq_customers_code_ci and
     * uq_customers_tax_id are partial unique indexes. These exist so the refusal
     * is a sentence a person can read instead of a constraint violation.
     */
    @Query("""
        SELECT COUNT(c) > 0 FROM Customer c
        WHERE LOWER(c.code) = LOWER(:code)
          AND (:excludeId IS NULL OR c.id <> :excludeId)
        """)
    boolean codeTakenByAnother(@Param("code") String code, @Param("excludeId") Long excludeId);

    @Query("""
        SELECT COUNT(c) > 0 FROM Customer c
        WHERE c.taxId = :taxId
          AND (:excludeId IS NULL OR c.id <> :excludeId)
        """)
    boolean taxIdTakenByAnother(@Param("taxId") String taxId, @Param("excludeId") Long excludeId);
}
