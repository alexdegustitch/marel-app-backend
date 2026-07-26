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
    @Query("""
            select r
            from UserRegistrationRequest r
            join fetch r.user u
            join fetch u.role
            left join fetch r.reviewedBy
            where (:status is null or r.status = :status)
            """)
    Page<UserRegistrationRequest> findPageByStatus(
            @Param("status") UserRegistrationRequestStatus status,
            Pageable pageable
    );

    Page<UserRegistrationRequest> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByStatus(UserRegistrationRequestStatus status);
}
