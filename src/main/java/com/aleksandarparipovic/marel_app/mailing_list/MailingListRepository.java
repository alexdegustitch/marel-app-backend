package com.aleksandarparipovic.marel_app.mailing_list;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MailingListRepository extends JpaRepository<MailingList, Long> {

    @Query("""
            select l from MailingList l
            join fetch l.ownerUser
            where l.id = :id
            """)
    Optional<MailingList> findDetailById(@Param("id") Long id);

    /**
     * Every list the user may actually use: their own, GLOBAL ones when they hold
     * the permission, and SHARED ones explicitly granted to them. Archived lists
     * are excluded — they cannot be attached to new orders.
     *
     * <p>Visibility is filtered in the query rather than in Java so a user can
     * never page through lists they are not allowed to see.
     */
    @Query("""
            select l from MailingList l
            join fetch l.ownerUser
            where l.archivedAt is null
              and (
                    l.ownerUser.id = :userId
                 or (l.visibility = com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility.GLOBAL
                     and :canSeeGlobal = true)
                 or (l.visibility = com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility.SHARED
                     and exists (
                         select 1 from MailingListAccess a
                         where a.mailingList.id = l.id and a.user.id = :userId))
              )
            """)
    Page<MailingList> findAccessible(
            @Param("userId") Long userId,
            @Param("canSeeGlobal") boolean canSeeGlobal,
            Pageable pageable
    );

    boolean existsByOwnerUser_IdAndNameIgnoreCaseAndArchivedAtIsNull(Long ownerId, String name);
}
