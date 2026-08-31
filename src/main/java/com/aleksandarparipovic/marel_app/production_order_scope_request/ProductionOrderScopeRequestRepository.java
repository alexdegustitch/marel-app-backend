package com.aleksandarparipovic.marel_app.production_order_scope_request;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderScopeRequestRepository
        extends JpaRepository<ProductionOrderScopeRequest, Long> {

    @Query("""
            select r
            from ProductionOrderScopeRequest r
            join fetch r.productionOrder
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.processedBy
            where r.id = :id
            """)
    Optional<ProductionOrderScopeRequest> findDetailById(@Param("id") Long id);

    /**
     * Claiming, saving and submitting take a row lock on top of the @Version
     * check.
     *
     * <p>Optimistic locking alone would let two processors both fill the modal
     * and only discover the conflict at flush — after one of them has rewritten
     * every operation row. A pessimistic lock makes the loser wait and re-read,
     * so only one of them ever does the work.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductionOrderScopeRequest r where r.id = :id")
    Optional<ProductionOrderScopeRequest> findByIdForUpdate(@Param("id") Long id);

    /**
     * The queue and the history list. Every filter is optional so one query backs
     * the whole screen; all are bound parameters, never concatenated.
     *
     * <p>The items are deliberately NOT fetched here — a bag fetch would make
     * the page size mean rows rather than requests. They are read for the page's
     * ids in one further query, see {@link #findItemsForRequests}.
     */
    @Query(value = """
            select r
            from ProductionOrderScopeRequest r
            join fetch r.productionOrder o
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.processedBy
            where (:status is null or r.status = :status)
              and (:productionOrderId is null or o.id = :productionOrderId)
              and (:createdById is null or r.createdBy.id = :createdById)
              and (:assignedToId is null or r.assignedTo.id = :assignedToId)
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
                    from ProductionOrderScopeRequest r
                    left join r.productionOrder o
                    where (:status is null or r.status = :status)
                      and (:productionOrderId is null or o.id = :productionOrderId)
                      and (:createdById is null or r.createdBy.id = :createdById)
                      and (:assignedToId is null or r.assignedTo.id = :assignedToId)
                      and (:mineUserId is null
                           or r.createdBy.id = :mineUserId
                           or r.assignedTo.id = :mineUserId)
                      and r.createdAt >= :createdFrom
                      and r.createdAt < :createdTo
                    """)
    Page<ProductionOrderScopeRequest> search(
            @Param("status") ProductionOrderScopeRequestStatus status,
            @Param("productionOrderId") Long productionOrderId,
            @Param("createdById") Long createdById,
            @Param("assignedToId") Long assignedToId,
            /** "Mine": raised by me OR taken by me — an OR, so not expressible above. */
            @Param("mineUserId") Long mineUserId,
            /**
             * Rank the statuses so a page reads as groups: what waits, then what
             * is being done, then what is finished. With a single status selected
             * the rank is constant and the order is simply the date one.
             */
            @Param("pending") ProductionOrderScopeRequestStatus pending,
            @Param("inReview") ProductionOrderScopeRequestStatus inReview,
            @Param("completed") ProductionOrderScopeRequestStatus completed,
            @Param("declined") ProductionOrderScopeRequestStatus declined,
            /**
             * Always bound, never null: an unset filter arrives as a range wide
             * enough to hold everything. A nullable timestamp would reach
             * PostgreSQL as an untyped NULL inside `is null`, which it refuses to
             * plan.
             */
            @Param("createdFrom") java.time.OffsetDateTime createdFrom,
            /** Exclusive, so a single chosen day is [00:00, next 00:00). */
            @Param("createdTo") java.time.OffsetDateTime createdTo,
            Pageable pageable
    );

    /**
     * What one order's scope requests are: those still running, and the answers
     * already given. Refused and withdrawn ones are left out — they leave the
     * order exactly as it was, still free to ask again.
     */
    @Query("""
            select r
            from ProductionOrderScopeRequest r
            join fetch r.productionOrder o
            join fetch r.createdBy
            left join fetch r.assignedTo
            left join fetch r.processedBy
            where o.id = :productionOrderId
              and r.status in :statuses
              and (:createdById is null or r.createdBy.id = :createdById)
            order by r.createdAt desc
            """)
    List<ProductionOrderScopeRequest> findByProductionOrderAndStatusIn(
            @Param("productionOrderId") Long productionOrderId,
            @Param("statuses") Collection<ProductionOrderScopeRequestStatus> statuses,
            @Param("createdById") Long createdById);

    /**
     * The covered lines of several requests at once, with their products already
     * loaded — one query for a whole page instead of one per row.
     *
     * <p>Callers must skip this when the id list is empty: an empty IN is not a
     * question the database accepts.
     */
    @Query("""
            select i
            from ProductionOrderScopeRequestItem i
            join fetch i.lineItem li
            join fetch li.product
            where i.request.id in :requestIds
            order by i.lineOrder asc, i.id asc
            """)
    List<ProductionOrderScopeRequestItem> findItemsForRequests(
            @Param("requestIds") Collection<Long> requestIds);

    /**
     * One request's lines with the answer already written on them. The operations
     * bag is fetched here and nowhere else, so the detail view costs one query
     * rather than one per line.
     */
    @Query("""
            select distinct i
            from ProductionOrderScopeRequestItem i
            join fetch i.lineItem li
            join fetch li.product
            left join fetch i.operations
            where i.request.id = :requestId
            """)
    List<ProductionOrderScopeRequestItem> findItemsWithOperations(
            @Param("requestId") Long requestId);

    /**
     * Whether any of these lines is already covered by a request that is still
     * open or already answered.
     *
     * <p>Both matter, and for different reasons: two open requests for one line
     * would have two supervisors deciding the same scope, and a line that already
     * HAS an agreed scope should be revised deliberately rather than by quietly
     * raising a second request beside the first.
     */
    @Query("""
            select distinct li.id
            from ProductionOrderScopeRequestItem i
            join i.lineItem li
            where li.id in :lineItemIds
              and i.request.status in :statuses
            """)
    List<Long> findCoveredLineItemIds(
            @Param("lineItemIds") Collection<Long> lineItemIds,
            @Param("statuses") Collection<ProductionOrderScopeRequestStatus> statuses);

    long countByStatus(ProductionOrderScopeRequestStatus status);
}
