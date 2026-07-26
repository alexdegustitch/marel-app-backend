package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ManufacturingTimeRequestRepository
        extends JpaRepository<ManufacturingTimeRequest, Long> {

    @Query("""
            select r
            from ManufacturingTimeRequest r
            join fetch r.product
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.processedBy
            left join fetch r.targetManufacturingTime
            where r.id = :id
            """)
    Optional<ManufacturingTimeRequest> findDetailById(@Param("id") Long id);

    /**
     * Claiming and processing take a row lock on top of the @Version check.
     *
     * <p>Optimistic locking alone would let both processors do all the work and
     * only discover the conflict at flush — including, for a completion, creating
     * a manufacturing-time record that then has to roll back. A pessimistic lock
     * makes the loser wait and re-read, so only one of them ever does the work.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ManufacturingTimeRequest r where r.id = :id")
    Optional<ManufacturingTimeRequest> findByIdForUpdate(@Param("id") Long id);

    /**
     * The processor queue and history list. Every filter is optional so one query
     * backs the whole screen; all are bound parameters, never concatenated.
     */
    @Query("""
            select r
            from ManufacturingTimeRequest r
            join fetch r.product
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.processedBy
            left join fetch r.targetManufacturingTime
            where (:status is null or r.status = :status)
              and (:productId is null or r.product.id = :productId)
              and (:createdById is null or r.createdBy.id = :createdById)
              and (:assignedToId is null or r.assignedTo.id = :assignedToId)
            """)
    Page<ManufacturingTimeRequest> search(
            @Param("status") ManufacturingTimeRequestStatus status,
            @Param("productId") Long productId,
            @Param("createdById") Long createdById,
            @Param("assignedToId") Long assignedToId,
            Pageable pageable
    );

    boolean existsByTargetManufacturingTime_IdAndStatusIn(
            Long targetId, java.util.Collection<ManufacturingTimeRequestStatus> statuses);

    long countByStatus(ManufacturingTimeRequestStatus status);
}
