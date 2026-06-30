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
public interface MonthlyRecalcQueueRepository extends JpaRepository<MonthlyRecalcQueue, Long> {

    boolean existsByEmployee_IdAndReportYearAndReportMonthAndStatusIn(
            Long employeeId, Integer year, Integer month, Collection<String> statuses);

    Optional<MonthlyRecalcQueue> findFirstByEmployee_IdAndReportYearAndReportMonthAndStatusIn(
            Long employeeId, Integer year, Integer month, Collection<String> statuses);

    /** Pessimistic WRITE lock — prevents concurrent workers from loading same job. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT mjq FROM MonthlyRecalcQueue mjq WHERE mjq.id = :id")
    Optional<MonthlyRecalcQueue> findByIdForUpdate(@Param("id") Long id);
}


