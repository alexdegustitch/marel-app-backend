package com.aleksandarparipovic.marel_app.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, Long> {

    /**
     * The one request still open for this user, if any.
     *
     * <p>At most one exists because {@code uq_email_change_requests_live} says so:
     * two live requests would mean two codes in two mailboxes, and which address
     * the account ended up at would depend on which mail somebody read first.
     *
     * <p>Includes requests that have TIMED OUT — expiry is a fact about the row,
     * not a reason to hide it. The caller distinguishes "expired" from "wrong
     * code", because those are different things to be told.
     */
    @Query("""
            select r from EmailChangeRequest r
            where r.user.id = :userId
              and r.confirmedAt is null
              and r.cancelledAt is null
            """)
    Optional<EmailChangeRequest> findOpenForUser(@Param("userId") Long userId);
}
