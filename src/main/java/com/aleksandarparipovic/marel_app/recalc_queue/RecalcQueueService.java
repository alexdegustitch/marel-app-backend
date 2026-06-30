package com.aleksandarparipovic.marel_app.recalc_queue;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.report_worker.RecalcWorkerProperties;
import com.aleksandarparipovic.marel_app.report_worker.RecalcWorkerWakeSignal;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecalcQueueService {

    private final RecalcWorkerProperties properties;
    private final EntityManager em;
    private final MeterRegistry meterRegistry;
    private final RecalcWorkerWakeSignal wakeSignal;

    /** 
     * Mark or upsert a daily recalculation job as PENDING.
     * Stores the current daily report version in the job for later comparison.
     */
    @Transactional
    public void enqueueDailyJob(WorkShift workShift, String reason) {
        em.createNativeQuery(
                        "INSERT INTO daily_report_recalc_queue (employee_id, work_shift_id, work_date, reason, status, requested_at, retry_count, last_error, claimed_at, claimed_by, version) " +
                                "VALUES (?1, ?2, ?3, ?4, 'PENDING', NOW(), 0, NULL, NULL, NULL, 1) " +
                                "ON CONFLICT (work_shift_id) DO UPDATE SET " +
                                "reason = EXCLUDED.reason, " +
                                "requested_at = NOW(), " +
                                "last_error = NULL, " +
                                "version = daily_report_recalc_queue.version + 1, " +
                                "status = CASE WHEN daily_report_recalc_queue.status = 'IN_PROGRESS' THEN 'IN_PROGRESS' ELSE 'PENDING' END, " +
                                "processed_at = CASE WHEN daily_report_recalc_queue.status = 'IN_PROGRESS' THEN daily_report_recalc_queue.processed_at ELSE NULL END, " +
                                "claimed_at = CASE WHEN daily_report_recalc_queue.status = 'IN_PROGRESS' THEN daily_report_recalc_queue.claimed_at ELSE NULL END, " +
                                "claimed_by = CASE WHEN daily_report_recalc_queue.status = 'IN_PROGRESS' THEN daily_report_recalc_queue.claimed_by ELSE NULL END")
                .setParameter(1, workShift.getEmployee().getId())
                .setParameter(2, workShift.getId())
                .setParameter(3, workShift.getWorkDate())
                .setParameter(4, reason)
                .executeUpdate();
        wakeSignal.signalAll();
    }

    /** 
     * Mark or upsert a monthly recalculation job as PENDING.
     * Idempotent – skips if already queued. Stores version for later comparison.
     */
    @Transactional
    public void enqueueMonthlyJob(Employee employee, int year, int month, String reason) {
        em.createNativeQuery(
                        "INSERT INTO monthly_report_recalc_queue (employee_id, report_year, report_month, report_date, reason, status, requested_at, retry_count, last_error, claimed_at, claimed_by, version) " +
                                "VALUES (?1, ?2, ?3, make_date(?2, ?3, 1), ?4, 'PENDING', NOW(), 0, NULL, NULL, NULL, 1) " +
                                "ON CONFLICT (employee_id, report_year, report_month) WHERE (status IN ('PENDING', 'IN_PROGRESS')) DO UPDATE SET " +
                                "reason = EXCLUDED.reason, " +
                                "requested_at = NOW(), " +
                                "last_error = NULL, " +
                                "retry_count = 0, " +
                                "version = monthly_report_recalc_queue.version + 1, " +
                                "status = CASE WHEN monthly_report_recalc_queue.status = 'IN_PROGRESS' THEN 'IN_PROGRESS' ELSE 'PENDING' END, " +
                                "processed_at = CASE WHEN monthly_report_recalc_queue.status = 'IN_PROGRESS' THEN monthly_report_recalc_queue.processed_at ELSE NULL END, " +
                                "claimed_at = CASE WHEN monthly_report_recalc_queue.status = 'IN_PROGRESS' THEN monthly_report_recalc_queue.claimed_at ELSE NULL END, " +
                                "claimed_by = CASE WHEN monthly_report_recalc_queue.status = 'IN_PROGRESS' THEN monthly_report_recalc_queue.claimed_by ELSE NULL END")
                .setParameter(1, employee.getId())
                .setParameter(2, year)
                .setParameter(3, month)
                .setParameter(4, reason)
                .executeUpdate();
        wakeSignal.signalAll();
    }

    /**
     * Fetch a single PENDING daily job using FOR UPDATE SKIP LOCKED,
     * mark it RUNNING in the same transaction, and return it.
     * Returns empty if no jobs available.
     */
    @Transactional
    public List<Long> claimDailyJobIds(int batchSize, String workerId) {
        List<Number> ids = em.createNativeQuery(
                        "WITH c AS (" +
                                " SELECT id FROM daily_report_recalc_queue" +
                                " WHERE status = 'PENDING'" +
                                "   AND requested_at <= NOW()" +
                                "   AND (retry_count IS NULL OR retry_count < ?1)" +
                                " ORDER BY requested_at, id" +
                                " LIMIT ?2" +
                                " FOR UPDATE SKIP LOCKED" +
                                ")" +
                                " UPDATE daily_report_recalc_queue q" +
                                " SET status = 'IN_PROGRESS', claimed_at = NOW(), claimed_by = ?3" +
                                " FROM c WHERE q.id = c.id" +
                                " RETURNING q.id")
                .setParameter(1, properties.getMaxRetry())
                .setParameter(2, batchSize)
                .setParameter(3, workerId)
                .getResultList();

        return ids.stream().map(Number::longValue).toList();
    }

    /**
     * Fetch a single PENDING monthly job using FOR UPDATE SKIP LOCKED,
     * mark it RUNNING in the same transaction, and return it.
     * Returns empty if no jobs available.
     */
    @Transactional
    public List<Long> claimMonthlyJobIds(int batchSize, String workerId) {
        List<Number> ids = em.createNativeQuery(
                        "WITH c AS (" +
                                " SELECT id FROM monthly_report_recalc_queue" +
                                " WHERE status = 'PENDING'" +
                                "   AND requested_at <= NOW()" +
                                "   AND (retry_count IS NULL OR retry_count < ?1)" +
                                " ORDER BY requested_at, id" +
                                " LIMIT ?2" +
                                " FOR UPDATE SKIP LOCKED" +
                                ")" +
                                " UPDATE monthly_report_recalc_queue q" +
                                " SET status = 'IN_PROGRESS', claimed_at = NOW(), claimed_by = ?3" +
                                " FROM c WHERE q.id = c.id" +
                                " RETURNING q.id")
                .setParameter(1, properties.getMaxRetry())
                .setParameter(2, batchSize)
                .setParameter(3, workerId)
                .getResultList();

        return ids.stream().map(Number::longValue).toList();
    }

    @Transactional
    public int requeueStuckDailyJobs(Duration timeout, int batchLimit) {
        @SuppressWarnings("unchecked")
        List<Number> recoveredIds = em.createNativeQuery(
                        "WITH c AS (" +
                                " SELECT id FROM daily_report_recalc_queue" +
                                " WHERE status = 'IN_PROGRESS'" +
                                "   AND claimed_at < NOW() - make_interval(secs => ?1)" +
                                " ORDER BY claimed_at" +
                                " LIMIT ?2" +
                                " FOR UPDATE SKIP LOCKED" +
                                ")" +
                                " UPDATE daily_report_recalc_queue q" +
                                " SET status = 'PENDING', " +
                                "     claimed_at = NULL, " +
                                "     claimed_by = NULL, " +
                                "     retry_count = COALESCE(q.retry_count, 0) + 1, " +
                                "     stuck_count = COALESCE(q.stuck_count, 0) + 1, " +
                                "     last_stuck_at = NOW(), " +
                                "     last_error = 'Recovered stuck IN_PROGRESS by watchdog', " +
                                "     requested_at = NOW() + (LEAST(300000, GREATEST(1, ?3) * (2 ^ LEAST(COALESCE(q.retry_count, 0) + 1, 10))) * INTERVAL '1 millisecond') " +
                                " FROM c WHERE q.id = c.id" +
                                " RETURNING q.id")
                .setParameter(1, timeout.toSeconds())
                .setParameter(2, batchLimit)
                .setParameter(3, Math.max(1L, properties.getBaseBackoffMs()))
                .getResultList();

        int count = recoveredIds.size();
        if (count > 0) {
            meterRegistry.counter("recalc.jobs.recovered", "type", "daily").increment(count);
            meterRegistry.counter("recalc.jobs.retry", "type", "daily", "source", "stuck_recovery").increment(count);
            log.warn("Recovered {} stuck daily jobs ids={}", count, recoveredIds.stream().map(Number::longValue).toList());
        }
        return count;
    }

    @Transactional
    public int requeueStuckMonthlyJobs(Duration timeout, int batchLimit) {
        @SuppressWarnings("unchecked")
        List<Number> recoveredIds = em.createNativeQuery(
                        "WITH c AS (" +
                                " SELECT id FROM monthly_report_recalc_queue" +
                                " WHERE status = 'IN_PROGRESS'" +
                                "   AND claimed_at < NOW() - make_interval(secs => ?1)" +
                                " ORDER BY claimed_at" +
                                " LIMIT ?2" +
                                " FOR UPDATE SKIP LOCKED" +
                                ")" +
                                " UPDATE monthly_report_recalc_queue q" +
                                " SET status = 'PENDING', " +
                                "     claimed_at = NULL, " +
                                "     claimed_by = NULL, " +
                                "     retry_count = COALESCE(q.retry_count, 0) + 1, " +
                                "     stuck_count = COALESCE(q.stuck_count, 0) + 1, " +
                                "     last_stuck_at = NOW(), " +
                                "     last_error = 'Recovered stuck IN_PROGRESS by watchdog', " +
                                "     requested_at = NOW() + (LEAST(300000, GREATEST(1, ?3) * (2 ^ LEAST(COALESCE(q.retry_count, 0) + 1, 10))) * INTERVAL '1 millisecond') " +
                                " FROM c WHERE q.id = c.id" +
                                " RETURNING q.id")
                .setParameter(1, timeout.toSeconds())
                .setParameter(2, batchLimit)
                .setParameter(3, Math.max(1L, properties.getBaseBackoffMs()))
                .getResultList();

        int count = recoveredIds.size();
        if (count > 0) {
            meterRegistry.counter("recalc.jobs.recovered", "type", "monthly").increment(count);
            meterRegistry.counter("recalc.jobs.retry", "type", "monthly", "source", "stuck_recovery").increment(count);
            log.warn("Recovered {} stuck monthly jobs ids={}", count, recoveredIds.stream().map(Number::longValue).toList());
        }
        return count;
    }

    @Transactional(readOnly = true)
    public long countPendingDaily() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'PENDING'")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countPendingMonthly() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM monthly_report_recalc_queue WHERE status = 'PENDING'")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countFailedDaily() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'FAILED'")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countFailedMonthly() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM monthly_report_recalc_queue WHERE status = 'FAILED'")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countInProgressDaily() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'IN_PROGRESS'")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countInProgressMonthly() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM monthly_report_recalc_queue WHERE status = 'IN_PROGRESS'")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countRetryingDaily() {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status IN ('PENDING','IN_PROGRESS') AND COALESCE(retry_count, 0) > 0")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long countRetryingMonthly() {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM monthly_report_recalc_queue WHERE status IN ('PENDING','IN_PROGRESS') AND COALESCE(retry_count, 0) > 0")
                .getSingleResult();
        return count.longValue();
    }

    @Transactional(readOnly = true)
    public long pendingAgeSecondsDaily() {
        Number seconds = (Number) em.createNativeQuery(
                        "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(requested_at))), 0) " +
                                "FROM daily_report_recalc_queue WHERE status = 'PENDING'")
                .getSingleResult();
        return seconds.longValue();
    }

    @Transactional(readOnly = true)
    public long pendingAgeSecondsMonthly() {
        Number seconds = (Number) em.createNativeQuery(
                        "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(requested_at))), 0) " +
                                "FROM monthly_report_recalc_queue WHERE status = 'PENDING'")
                .getSingleResult();
        return seconds.longValue();
    }

    @Transactional
    public int cleanupDoneDailyJobs(long retentionDays, int batchSize) {
        Number deleted = (Number) em.createNativeQuery(
                        "WITH c AS (" +
                                " SELECT id FROM daily_report_recalc_queue" +
                                " WHERE status = 'DONE'" +
                                "   AND processed_at IS NOT NULL" +
                                "   AND processed_at < NOW() - (?1 * INTERVAL '1 day')" +
                                " ORDER BY processed_at, id" +
                                " LIMIT ?2" +
                                " FOR UPDATE SKIP LOCKED" +
                                "), d AS (" +
                                " DELETE FROM daily_report_recalc_queue q" +
                                " USING c WHERE q.id = c.id" +
                                " RETURNING q.id" +
                                ") SELECT COUNT(*) FROM d")
                .setParameter(1, Math.max(1L, retentionDays))
                .setParameter(2, Math.max(1, batchSize))
                .getSingleResult();
        return deleted.intValue();
    }

    @Transactional
    public int cleanupDoneMonthlyJobs(long retentionDays, int batchSize) {
        Number deleted = (Number) em.createNativeQuery(
                        "WITH c AS (" +
                                " SELECT id FROM monthly_report_recalc_queue" +
                                " WHERE status = 'DONE'" +
                                "   AND processed_at IS NOT NULL" +
                                "   AND processed_at < NOW() - (?1 * INTERVAL '1 day')" +
                                " ORDER BY processed_at, id" +
                                " LIMIT ?2" +
                                " FOR UPDATE SKIP LOCKED" +
                                "), d AS (" +
                                " DELETE FROM monthly_report_recalc_queue q" +
                                " USING c WHERE q.id = c.id" +
                                " RETURNING q.id" +
                                ") SELECT COUNT(*) FROM d")
                .setParameter(1, Math.max(1L, retentionDays))
                .setParameter(2, Math.max(1, batchSize))
                .getSingleResult();
        return deleted.intValue();
    }
}
