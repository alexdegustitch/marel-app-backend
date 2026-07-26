package com.aleksandarparipovic.marel_app.mailing_list_member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailingListMemberRepository extends JpaRepository<MailingListMember, Long> {

    /**
     * Active members with the user side fetched, because building a snapshot needs
     * each user's current email and name.
     */
    @Query("""
            select m from MailingListMember m
            left join fetch m.user
            where m.mailingList.id = :mailingListId
              and m.archivedAt is null
            order by m.id
            """)
    List<MailingListMember> findActiveByMailingListId(@Param("mailingListId") Long mailingListId);

    @Query("""
            select m from MailingListMember m
            left join fetch m.user
            where m.id = :id
            """)
    Optional<MailingListMember> findDetailById(@Param("id") Long id);

    boolean existsByMailingList_IdAndUser_IdAndArchivedAtIsNull(Long mailingListId, Long userId);

    boolean existsByMailingList_IdAndExternalEmailAndArchivedAtIsNull(
            Long mailingListId, String externalEmail);

    /**
     * The cross-source duplicate check a database constraint cannot express: does
     * some USER member of this list already resolve to this address?
     */
    @Query("""
            select count(m) > 0 from MailingListMember m
            where m.mailingList.id = :mailingListId
              and m.archivedAt is null
              and m.user is not null
              and lower(m.user.emailAddress) = :email
            """)
    boolean existsActiveUserMemberWithEmail(
            @Param("mailingListId") Long mailingListId, @Param("email") String email);
}
