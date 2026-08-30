package com.aleksandarparipovic.marel_app.sample_order_mailing_list;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SampleOrderMailingListRepository extends JpaRepository<SampleOrderMailingList, Long> {

    @Query("""
            select l from SampleOrderMailingList l
            join fetch l.mailingList
            where l.sampleOrder.id = :sampleOrderId
            order by l.id
            """)
    List<SampleOrderMailingList> findBySampleOrderId(@Param("sampleOrderId") Long sampleOrderId);

    Optional<SampleOrderMailingList> findBySampleOrder_IdAndMailingList_Id(
            Long sampleOrderId, Long mailingListId);

    boolean existsBySampleOrder_IdAndMailingList_Id(Long sampleOrderId, Long mailingListId);
}
