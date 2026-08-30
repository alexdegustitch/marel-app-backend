package com.aleksandarparipovic.marel_app.payroll_change_request;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollChangeRequestRepository extends JpaRepository<PayrollChangeRequest, Long> {

    /** The one still waiting on this payroll, if there is one. */
    Optional<PayrollChangeRequest> findByPayrollRunItem_IdAndStatus(
            Long payrollRunItemId, PayrollChangeRequestStatus status);

    /**
     * One page of the queue, or of one person's own.
     *
     * <p>Both narrowings are OPTIONAL parameters of one query rather than two
     * methods: the screen asks the same question — "the requests I may see, in
     * this status" — and which of the two it means is decided by who is asking,
     * not by which endpoint they called.
     *
     * <p>{@code status} null means every status. The requests screen groups by
     * status and asks for one group at a time, but a reader looking at their own
     * history wants the lot.
     *
     * <p>{@code requestedById} null means "no narrowing" — the queue. Set to the
     * caller's own id for anybody who may not answer these.
     */
    @Query(value = """
            select r from PayrollChangeRequest r
            join fetch r.requestedBy
            join fetch r.payrollRunItem item
            join fetch item.employee
            left join fetch r.decidedBy
            where (:status is null or r.status = :status)
              and (:requestedById is null or r.requestedBy.id = :requestedById)
            order by r.requestedAt desc
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
                    from PayrollChangeRequest r
                    where (:status is null or r.status = :status)
                      and (:requestedById is null or r.requestedBy.id = :requestedById)
                    """)
    Page<PayrollChangeRequest> search(
            @Param("status") PayrollChangeRequestStatus status,
            @Param("requestedById") Long requestedById,
            Pageable pageable);

    @Query("""
            select r from PayrollChangeRequest r
            join fetch r.requestedBy
            join fetch r.payrollRunItem item
            join fetch item.employee
            left join fetch r.decidedBy
            where r.id = :id
            """)
    Optional<PayrollChangeRequest> findDetailById(@Param("id") Long id);

    /** What one payroll's own page shows beside its history. */
    List<PayrollChangeRequest> findByPayrollRunItem_IdOrderByRequestedAtDesc(Long payrollRunItemId);
}
