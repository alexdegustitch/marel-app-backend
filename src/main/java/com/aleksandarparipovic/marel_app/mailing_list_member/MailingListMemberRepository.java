package com.aleksandarparipovic.marel_app.mailing_list_member;

import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
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

    /**
     * The lists a given user is an ACTIVE member of — the reverse of
     * {@link #findActiveByMailingListId}, for a colleague's profile. The owner side
     * is fetched because the caller filters each list by read access, which asks
     * who owns it. Archived lists are left in; the caller drops them.
     */
    @Query("""
            select m.mailingList from MailingListMember m
            join fetch m.mailingList.ownerUser
            where m.user.id = :userId
              and m.archivedAt is null
            order by m.mailingList.name
            """)
    List<MailingList> findActiveListsByUserId(@Param("userId") Long userId);

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
