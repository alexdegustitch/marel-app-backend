package com.aleksandarparipovic.marel_app.production_order_mailing_list;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderMailingListRepository
        extends JpaRepository<ProductionOrderMailingList, Long> {

    @Query("""
            select l from ProductionOrderMailingList l
            join fetch l.mailingList
            where l.productionOrder.id = :productionOrderId
            order by l.id
            """)
    List<ProductionOrderMailingList> findByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId);

    Optional<ProductionOrderMailingList> findByProductionOrder_IdAndMailingList_Id(
            Long productionOrderId, Long mailingListId);

    boolean existsByProductionOrder_IdAndMailingList_Id(Long productionOrderId, Long mailingListId);
}
