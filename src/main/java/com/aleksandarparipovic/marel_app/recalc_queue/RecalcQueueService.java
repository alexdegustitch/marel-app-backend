package com.aleksandarparipovic.marel_app.recalc_queue;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecalcQueueService {

    private static final int MAX_RETRY = 5;
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "PROCESSING");

    private final DailyRecalcQueueRepository dailyRepo;
    private final MonthlyRecalcQueueRepository monthlyRepo;
    private final EntityManager em;

    /** Enqueue a daily recalculation for the given shift (idempotent – skips if already queued). */
    @Transactional
    public void enqueueDailyJob(WorkShift workShift, String reason) {
        if (dailyRepo.existsByWorkShift_IdAndStatusIn(workShift.getId(), ACTIVE_STATUSES)) {
            log.debug("Daily recalc already queued for shift {}", workShift.getId());
            return;
        }
        DailyRecalcQueue job = DailyRecalcQueue.builder()
                .workShift(workShift)
                .employee(workShift.getEmployee())
                .workDate(workShift.getWorkDate())
                .reason(reason)
                .status("PENDING")
                .retryCount(0)
                .requestedAt(OffsetDateTime.now())
                .build();
        dailyRepo.save(job);
        log.debug("Enqueued daily recalc for shift {}", workShift.getId());
    }

    /** Enqueue a monthly recalculation for the given employee / period (idempotent). */
    @Transactional
    public void enqueueMonthlyJob(Employee employee, int year, int month, String reason) {
        if (monthlyRepo.existsByEmployee_IdAndReportYearAndReportMonthAndStatusIn(
                employee.getId(), year, month, ACTIVE_STATUSES)) {
            log.debug("Monthly recalc already queued for emp={} {{}} / {{}}",
                    employee.getId(), year, month);
            return;
        }
        MonthlyRecalcQueue job = MonthlyRecalcQueue.builder()
                .employee(employee)
                .reportYear(year)
                .reportMonth(month)
                .reason(reason)
                .status("PENDING")
                .retryCount(0)
                .requestedAt(OffsetDateTime.now())
                .build();
        monthlyRepo.save(job);
        log.debug("Enqueued monthly recalc for emp={} {} / {}", employee.getId(), year, month);
    }

    /**
     * Claims up to batchSize PENDING daily jobs using FOR UPDATE SKIP LOCKED,
     * marks them PROCESSING in the same transaction, and returns their IDs.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Long> claimDailyJobIds(int batchSize, String workerId) {
        List<Number> rawIds = em.createNativeQuery(
                "SELECT id FROM daily_report_recalc_queue " +
                "WHERE status = 'PENDING' AND (retry_count IS NULL OR retry_count < :maxRetry) " +
                "ORDER BY requested_at " +
                "LIMIT :limit " +
                "FOR UPDATE SKIP LOCKED")
                .setParameter("maxRetry", MAX_RETRY)
                .setParameter("limit", batchSize)
                .getResultList();

        if (rawIds.isEmpty()) return List.of();

        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        String idsCsv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));

        em.createNativeQuery(
                "UPDATE daily_report_recalc_queue " +
                "SET status = 'PROCESSING', locked_at = NOW(), locked_by = :workerId " +
                "WHERE id IN (" + idsCsv + ")")
                .setParameter("workerId", workerId)
                .executeUpdate();

        return ids;
    }

    /**
     * Claims up to batchSize PENDING monthly jobs using FOR UPDATE SKIP LOCKED,
     * marks them PROCESSING in the same transaction, and returns their IDs.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Long> claimMonthlyJobIds(int batchSize, String workerId) {
        List<Number> rawIds = em.createNativeQuery(
                "SELECT id FROM monthly_report_recalc_queue " +
                "WHERE status = 'PENDING' AND (retry_count IS NULL OR retry_count < :maxRetry) " +
                "ORDER BY requested_at " +
                "LIMIT :limit " +
                "FOR UPDATE SKIP LOCKED")
                .setParameter("maxRetry", MAX_RETRY)
                .setParameter("limit", batchSize)
                .getResultList();

        if (rawIds.isEmpty()) return List.of();

        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        String idsCsv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));

        em.createNativeQuery(
                "UPDATE monthly_report_recalc_queue " +
                "SET status = 'PROCESSING', locked_at = NOW(), locked_by = :workerId " +
                "WHERE id IN (" + idsCsv + ")")
                .setParameter("workerId", workerId)
                .executeUpdate();

        return ids;
    }
}
