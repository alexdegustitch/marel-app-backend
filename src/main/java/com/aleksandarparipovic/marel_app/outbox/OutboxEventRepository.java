package com.aleksandarparipovic.marel_app.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of due events for this worker instance.
     *
     * <p>Also reclaims rows stranded in PROCESSING by a crashed instance, once
     * they are older than the stuck timeout — otherwise a crash mid-batch would
     * leave those events invisible forever. Reprocessing them is safe because
     * fan-out is idempotent.
     *
     * <p>FOR UPDATE SKIP LOCKED is the same pattern RecalcQueueService already uses:
     * several application instances can poll the same table concurrently and each
     * one walks away with a disjoint batch instead of blocking on the others. Rows
     * another worker holds are skipped, never waited on.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (
                    (status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now)
                 OR (status = 'PROCESSING' AND next_attempt_at <= :stuckBefore)
                  )
            ORDER BY next_attempt_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(
            @Param("now") OffsetDateTime now,
            @Param("stuckBefore") OffsetDateTime stuckBefore,
            @Param("batchSize") int batchSize
    );

    List<OutboxEvent> findByStatusOrderByCreatedAtDesc(OutboxEventStatus status, Pageable pageable);

    long countByStatus(OutboxEventStatus status);
}
