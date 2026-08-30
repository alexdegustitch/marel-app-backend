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
            left join fetch r.resultManufacturingTime
            left join fetch r.productionOrderLineItem lineItem
            left join fetch lineItem.productionOrder productionOrder
            left join fetch r.sampleOrderLineItem sampleLineItem
            left join fetch sampleLineItem.sampleOrder sampleOrder
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
    @Query(value = """
            select r
            from ManufacturingTimeRequest r
            join fetch r.product
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.processedBy
            left join fetch r.targetManufacturingTime
            left join fetch r.resultManufacturingTime
            left join fetch r.productionOrderLineItem lineItem
            left join fetch lineItem.productionOrder productionOrder
            left join fetch r.sampleOrderLineItem sampleLineItem
            left join fetch sampleLineItem.sampleOrder sampleOrder
            where (:status is null or r.status = :status)
              and (:productId is null or r.product.id = :productId)
              and (:createdById is null or r.createdBy.id = :createdById)
              and (:assignedToId is null or r.assignedTo.id = :assignedToId)
              and (:productionOrderId is null
                   or productionOrder.id = :productionOrderId)
              and (:mineUserId is null
                   or r.createdBy.id = :mineUserId
                   or r.assignedTo.id = :mineUserId)
              and r.createdAt >= :createdFrom
              and r.createdAt < :createdTo
            order by case r.status
                         when :pending then 0
                         when :inReview then 1
                         when :completed then 2
                         when :declined then 3
                         else 4
                     end
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
                    from ManufacturingTimeRequest r
                    left join r.productionOrderLineItem lineItem
                    left join lineItem.productionOrder productionOrder
                    where (:status is null or r.status = :status)
                      and (:productId is null or r.product.id = :productId)
                      and (:createdById is null or r.createdBy.id = :createdById)
                      and (:assignedToId is null or r.assignedTo.id = :assignedToId)
                      and (:productionOrderId is null
                           or productionOrder.id = :productionOrderId)
                      and (:mineUserId is null
                           or r.createdBy.id = :mineUserId
                           or r.assignedTo.id = :mineUserId)
                      and r.createdAt >= :createdFrom
                      and r.createdAt < :createdTo
                    """)
    Page<ManufacturingTimeRequest> search(
            @Param("status") ManufacturingTimeRequestStatus status,
            @Param("productId") Long productId,
            @Param("createdById") Long createdById,
            @Param("assignedToId") Long assignedToId,
            @Param("productionOrderId") Long productionOrderId,
            /**
             * "Mine": raised by me OR taken by me. An OR, which is why it cannot
             * be expressed with createdById and assignedToId — those AND with
             * everything else, and asking for both would mean requests a person
             * both raised and is processing, which the workflow forbids anyway.
             */
            @Param("mineUserId") Long mineUserId,
            /**
             * Rank the statuses so a page reads as groups: what waits, then what
             * is being done, then what is finished. With a single status
             * selected the rank is constant and the order is simply newest-first,
             * so one query serves both views.
             */
            @Param("pending") ManufacturingTimeRequestStatus pending,
            @Param("inReview") ManufacturingTimeRequestStatus inReview,
            @Param("completed") ManufacturingTimeRequestStatus completed,
            @Param("declined") ManufacturingTimeRequestStatus declined,
            /**
             * Always bound, never null: an unset filter arrives as a range wide
             * enough to hold everything. A nullable timestamp here would reach
             * PostgreSQL as an untyped NULL inside `is null`, which it refuses to
             * plan.
             */
            @Param("createdFrom") java.time.OffsetDateTime createdFrom,
            /** Exclusive, so a single chosen day is [00:00, next 00:00). */
            @Param("createdTo") java.time.OffsetDateTime createdTo,
            /**
             * Only the GROUP order lives in the query. The date direction rides in
             * on the Pageable's Sort, which Spring appends after this ORDER BY —
             * a direction cannot be a query parameter, and writing both out with a
             * CASE leaves PostgreSQL unable to type it.
             */
            Pageable pageable
    );

    /**
     * What one order's lines have to say about their manufacturing time: the
     * requests still running on them, and the ones already answered. Refused and
     * withdrawn requests are left out — they leave the line exactly as it was,
     * still free to ask again.
     *
     * <p>{@code createdById} narrows the answer to one person's own requests,
     * which is what a caller without the read-all permission is allowed to see.
     */
    @Query("""
            select r
            from ManufacturingTimeRequest r
            join fetch r.product
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.resultManufacturingTime
            join fetch r.productionOrderLineItem lineItem
            join fetch lineItem.productionOrder productionOrder
            where productionOrder.id = :productionOrderId
              and r.status in :statuses
              and (:createdById is null or r.createdBy.id = :createdById)
            """)
    java.util.List<ManufacturingTimeRequest> findByProductionOrderAndStatusIn(
            @Param("productionOrderId") Long productionOrderId,
            @Param("statuses") java.util.Collection<ManufacturingTimeRequestStatus> statuses,
            @Param("createdById") Long createdById);

    /**
     * What the manufacturing-time screen offers a processor to pick up: work that
     * is free to take, plus the work they already took.
     *
     * <p>Deliberately NOT every open request — a request somebody else is
     * processing is not on offer, and listing it only invites two people to do
     * the same job. {@code createdById} narrows the answer further to one
     * person's own requests, which is what a caller without the read-all
     * permission may see.
     */
    @Query("""
            select r
            from ManufacturingTimeRequest r
            join fetch r.product
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.targetManufacturingTime
            left join fetch r.productionOrderLineItem lineItem
            left join fetch lineItem.productionOrder productionOrder
            left join fetch r.sampleOrderLineItem sampleLineItem
            left join fetch sampleLineItem.sampleOrder sampleOrder
            where (r.status = :pending
                   or (r.status = :inReview and r.assignedTo.id = :actorId))
              and (:createdById is null or r.createdBy.id = :createdById)
            order by r.createdAt desc
            """)
    java.util.List<ManufacturingTimeRequest> findPickable(
            @Param("pending") ManufacturingTimeRequestStatus pending,
            @Param("inReview") ManufacturingTimeRequestStatus inReview,
            @Param("actorId") Long actorId,
            @Param("createdById") Long createdById);

    /**
     * The same question for a sample order: what its lines have to say about
     * their manufacturing time. Refused and withdrawn requests are left out —
     * they leave the line exactly as it was, still free to ask again.
     *
     * <p>{@code createdById} narrows the answer to one person's own requests,
     * which is what a caller without the read-all permission is allowed to see.
     */
    @Query("""
            select r
            from ManufacturingTimeRequest r
            join fetch r.product
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.resultManufacturingTime
            join fetch r.sampleOrderLineItem lineItem
            join fetch lineItem.sampleOrder sampleOrder
            where sampleOrder.id = :sampleOrderId
              and r.status in :statuses
              and (:createdById is null or r.createdBy.id = :createdById)
            """)
    java.util.List<ManufacturingTimeRequest> findBySampleOrderAndStatusIn(
            @Param("sampleOrderId") Long sampleOrderId,
            @Param("statuses") java.util.Collection<ManufacturingTimeRequestStatus> statuses,
            @Param("createdById") Long createdById);

    boolean existsBySampleOrderLineItem_IdAndStatusIn(
            Long lineItemId, java.util.Collection<ManufacturingTimeRequestStatus> statuses);

    boolean existsByProductionOrderLineItem_IdAndStatusIn(
            Long lineItemId, java.util.Collection<ManufacturingTimeRequestStatus> statuses);

    boolean existsByTargetManufacturingTime_IdAndStatusIn(
            Long targetId, java.util.Collection<ManufacturingTimeRequestStatus> statuses);

    long countByStatus(ManufacturingTimeRequestStatus status);
}
