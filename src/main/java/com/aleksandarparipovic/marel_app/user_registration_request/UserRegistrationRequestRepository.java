package com.aleksandarparipovic.marel_app.user_registration_request;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRegistrationRequestRepository
        extends JpaRepository<UserRegistrationRequest, Long> {

    boolean existsByUser_IdAndStatus(Long userId, UserRegistrationRequestStatus status);

    /**
     * Detail read. Fetches both user sides eagerly because the response DTO always
     * renders the applicant and (when present) the reviewer.
     */
    @Query("""
            select r
            from UserRegistrationRequest r
            join fetch r.user u
            join fetch u.role
            left join fetch r.reviewedBy
            where r.id = :id
            """)
    Optional<UserRegistrationRequest> findDetailById(@Param("id") Long id);

    /**
     * Admin review list. Status is optional so one query backs both the default
     * "pending" queue and the "show everything" history view; paginated because an
     * unbounded request list must never be returned.
     */
    @Query(value = """
            select r
            from UserRegistrationRequest r
            join fetch r.user u
            join fetch u.role
            left join fetch r.reviewedBy
            where (:status is null or r.status = :status)
            """,
            /*
             * COUNTED SEPARATELY, ON PURPOSE.
             *
             * Spring derives a count query from the one above when none is given,
             * and its derivation turns every `left join fetch` into an INNER join.
             * The fetches here are all optional — an unclaimed request has no
             * assignee, a free-standing one has no order line — so the derived
             * count silently dropped exactly those rows. Worse than a wrong number:
             * when the count comes back SMALLER than the page already holds,
             * PageImpl decides the count cannot be right and reports
             * `offset + rows on this page` instead. The total then equals the page
             * size whatever the queue holds, "prikazi jos" never appears because
             * `shown < total` is never true, and answering a request does not move
             * the number.
             *
             * So: no fetches here, and only the joins the filters actually read.
             */
            countQuery = """
                    select count(r)
                    from UserRegistrationRequest r
                    where (:status is null or r.status = :status)
                    """)
    Page<UserRegistrationRequest> findPageByStatus(
            @Param("status") UserRegistrationRequestStatus status,
            Pageable pageable
    );

    Page<UserRegistrationRequest> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByStatus(UserRegistrationRequestStatus status);
}
