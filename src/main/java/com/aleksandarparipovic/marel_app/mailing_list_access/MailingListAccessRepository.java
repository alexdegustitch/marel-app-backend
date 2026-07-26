package com.aleksandarparipovic.marel_app.mailing_list_access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MailingListAccessRepository
        extends JpaRepository<MailingListAccess, MailingListAccessId> {

    boolean existsByMailingList_IdAndUser_Id(Long mailingListId, Long userId);

    @Query("""
            select a from MailingListAccess a
            join fetch a.user
            where a.mailingList.id = :mailingListId
            """)
    List<MailingListAccess> findByMailingListId(@Param("mailingListId") Long mailingListId);

    void deleteByMailingList_IdAndUser_Id(Long mailingListId, Long userId);
}
