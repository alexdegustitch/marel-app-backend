package com.aleksandarparipovic.marel_app.recalc_queue;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface DailyRecalcQueueRepository extends JpaRepository<DailyRecalcQueue, Long> {

    boolean existsByWorkShift_IdAndStatusIn(Long workShiftId, Collection<String> statuses);

    Optional<DailyRecalcQueue> findFirstByWorkShift_IdAndStatusIn(Long workShiftId, Collection<String> statuses);

    /** Pessimistic WRITE lock — prevents concurrent workers from loading same job. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT djq FROM DailyRecalcQueue djq WHERE djq.id = :id")
    Optional<DailyRecalcQueue> findByIdForUpdate(@Param("id") Long id);
}


