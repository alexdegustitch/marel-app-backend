package com.aleksandarparipovic.marel_app.sample_order.repository;

import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.search.dto.SampleOrderSearchRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SampleOrderRepository extends JpaRepository<SampleOrder, Long>, JpaSpecificationExecutor<SampleOrder> {

    List<SampleOrder> findByIsActiveIsTrueOrderByNameAsc();

    /**
     * Sample orders whose name or code contains {@code q}, for the global
     * command-palette search. Case-insensitive, non-archived orders only, exact
     * code matches first; the customer name rides along as the subtitle. The
     * caller caps the page.
     */
    @Query("""
            select s.id as id,
                   s.name as name,
                   s.code as code,
                   c.name as customerName
            from SampleOrder s
            left join s.customer c
            where s.archivedAt is null
              and (lower(s.name) like lower(concat('%', :q, '%'))
                or lower(s.code) like lower(concat('%', :q, '%')))
            order by case when lower(s.code) = lower(:q) then 0 else 1 end,
                     s.name asc, s.id asc
            """)
    List<SampleOrderSearchRow> searchTop(@Param("q") String q, Pageable pageable);
}
