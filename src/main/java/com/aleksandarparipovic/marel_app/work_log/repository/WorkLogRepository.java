package com.aleksandarparipovic.marel_app.work_log.repository;

import com.aleksandarparipovic.marel_app.summary.dto.DailySummaryProjection;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogPreviewDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface WorkLogRepository extends JpaRepository<WorkLog, Long>, JpaSpecificationExecutor<WorkLog> {

    /**
     * Whether this employee has any work log of a WORK category.
     *
     * <p>Asked before the employment start date may be moved: once real work is
     * recorded, the start date is no longer a free-standing field but the anchor
     * of everything calculated after it.
     *
     * <p>Deliberately NOT filtered by {@code wl.is_active}: a log that was
     * entered and later deactivated still means work was recorded against this
     * employee, and re-opening the date on the strength of a deletion is the
     * wrong direction to be lenient in. The SOURCE category is the one asked —
     * what the employee actually worked, not a night/weekend remap of it.
     */
    @Query(value = """
    SELECT EXISTS (
        SELECT 1
        FROM work_logs wl
        JOIN work_shifts ws ON ws.id = wl.work_shift_id
        JOIN work_code_categories wcc ON wcc.id = wl.work_code_category_id
        WHERE ws.employee_id = :employeeId
          AND upper(wcc.type) = 'WORK'
    )
    """, nativeQuery = true)
    boolean existsWorkTypeLogForEmployee(@Param("employeeId") Long employeeId);

    @Query(value = """
    SELECT
        wl.id AS id, wl.work_shift_id AS shiftId,
        o.id AS operationId, o.op_name AS operationName, o.min_norm AS minNorm,
        po.id AS productionOrderId, po.name AS productionOrderName, po.code AS productionOrderCode,
        p.id AS productId, p.product_name AS productName,
        wl.performance_rate as performanceRate,
        wl.approved_performance_rate as approvedPerformanceRate,
        wl.start_at AS startAt, wl.end_at AS endAt,
        wl.duration_min AS durationMin, wl.quantity AS quantity,
        wl.scrap AS scrap, wl.note AS note,
        wl.hourly_output AS hourlyOutput,
        wl.work_code_category_id as workCodeCategoryId,
        wcc.category_no as workCodeCategoryNo,
        wl.effective_work_code_category_id as effectiveWorkCodeCategoryId,
        ewcc.category_no as effectiveWorkCodeCategoryNo,
        wl.is_active as isActive,
        wl.norm_multiplier_snapshot as normMultiplierSnapshot,
        wcc.allows_parallel_work as allowsParallelWork
    FROM work_logs wl
    LEFT JOIN operations o ON wl.operation_id = o.id
    LEFT JOIN production_orders po ON wl.production_order_id = po.id
    LEFT JOIN products p ON o.product_id = p.id
    LEFT JOIN work_code_categories wcc ON wl.work_code_category_id = wcc.id
    LEFT JOIN work_code_categories ewcc ON wl.effective_work_code_category_id = ewcc.id
    WHERE wl.work_shift_id IN (:shiftIds) AND wl.is_active = true
    ORDER BY wl.work_shift_id, wl.start_at
    """, nativeQuery = true)
    List<WorkLogDto> getLogsForShifts(List<Long> shiftIds);

    @Query(value = """
    SELECT
        wl.id AS id, wl.work_shift_id AS shiftId,
        o.id AS operationId, o.op_name AS operationName, o.min_norm AS minNorm,
        po.id AS productionOrderId, po.name AS productionOrderName, po.code AS productionOrderCode,
        p.id AS productId, p.product_name AS productName,
        wl.performance_rate as performanceRate,
        wl.approved_performance_rate as approvedPerformanceRate,
        wl.start_at AS startAt, wl.end_at AS endAt,
        wl.duration_min AS durationMin, wl.quantity AS quantity,
        wl.scrap AS scrap, wl.note AS note,
        wl.hourly_output AS hourlyOutput,
        wl.work_code_category_id as workCodeCategoryId,
        wcc.category_no as workCodeCategoryNo,
        wl.effective_work_code_category_id as effectiveWorkCodeCategoryId,
        ewcc.category_no as effectiveWorkCodeCategoryNo,
        wl.is_active as isActive,
        wl.norm_multiplier_snapshot as normMultiplierSnapshot,
        wcc.allows_parallel_work as allowsParallelWork
    FROM work_logs wl
    LEFT JOIN operations o ON wl.operation_id = o.id
    LEFT JOIN production_orders po ON wl.production_order_id = po.id
    LEFT JOIN products p ON o.product_id = p.id
    LEFT JOIN work_code_categories wcc ON wl.work_code_category_id = wcc.id
    LEFT JOIN work_code_categories ewcc ON wl.effective_work_code_category_id = ewcc.id
    WHERE wl.work_shift_id = :shiftId AND wl.is_active = true
    ORDER BY wl.work_shift_id, wl.start_at
    """, nativeQuery = true)
    List<WorkLogDto> getAllActiveLogsForShift(Long shiftId);

    @Query(value = """
    SELECT wl.id AS id, wl.work_shift_id AS shiftId,
        o.id AS operationId, o.op_name AS operationName,
        wl.start_at AS startAt, wl.end_at AS endAt,
        wl.duration_min AS durationMin
    FROM work_logs wl
    LEFT JOIN operations o ON wl.operation_id = o.id
    WHERE wl.work_shift_id IN (:shiftIds) AND wl.is_active = true
    ORDER BY wl.work_shift_id, wl.start_at, wl.end_at
    """, nativeQuery = true)
    List<WorkLogPreviewDto> getLogsPreviewForShifts(List<Long> shiftIds);

    @Query("SELECT wl FROM WorkLog wl LEFT JOIN FETCH wl.workCode LEFT JOIN FETCH wl.operation WHERE wl.workShift.id = :shiftId AND wl.isActive = true")
    List<WorkLog> findActiveLogsWithCodeForShift(@Param("shiftId") Long shiftId);

    // Also fetches operation.product and productionOrder so consumers that need those
    // (e.g. AnalyticsFactSyncService) avoid an N+1 lazy-load per log.
    @Query("SELECT wl FROM WorkLog wl" +
            " LEFT JOIN FETCH wl.workCode" +
            " LEFT JOIN FETCH wl.operation op" +
            " LEFT JOIN FETCH op.product" +
            " LEFT JOIN FETCH wl.workShift" +
            " LEFT JOIN FETCH wl.productionOrder" +
            " WHERE wl.workShift.id = :shiftId AND wl.isActive = true")
    List<WorkLog> findActiveLogsWithRefsForShift(@Param("shiftId") Long shiftId);

    // Resets the bonus-effective category for active logs of a shift, so recalc can
    // rebuild it from scratch (guarantees revert when a condition stops applying).
    // Only touches rows that actually have a value, so the common no-remap case writes
    // nothing and does not fire the work_logs activity trigger.
    @Modifying
    @Query("UPDATE WorkLog wl SET wl.effectiveWorkCode = null WHERE wl.workShift.id = :shiftId AND wl.isActive = true AND wl.effectiveWorkCode IS NOT NULL")
    void clearEffectiveWorkCodeForShift(@Param("shiftId") Long shiftId);

    @Modifying
    @Query("UPDATE WorkLog wl SET wl.effectiveWorkCode = :category WHERE wl.id = :id")
    void setEffectiveWorkCode(@Param("id") Long id, @Param("category") WorkCodeCategory category);

    @Query(value = """
        SELECT ws.id AS workShiftId, ws.employee_id AS employeeId,
            ws.work_date AS workDate, ws.total_minutes AS totalShiftMinutes,
            COALESCE(SUM(wl.duration_min), 0) AS totalWorkMinutes,
            COALESCE(SUM(wl.quantity), 0) AS totalQuantity,
            COALESCE(SUM(wl.scrap), 0) AS totalScrap
        FROM work_shifts ws
        LEFT JOIN work_logs wl ON wl.work_shift_id = ws.id AND wl.is_active = true
        WHERE ws.id = :workShiftId
        GROUP BY ws.id, ws.employee_id, ws.work_date, ws.total_minutes
        """, nativeQuery = true)
    DailySummaryProjection getDailySummaryByShift(@Param("workShiftId") Long workShiftId);

    @Query("SELECT MIN(wl.startAt) AS minStart, MAX(wl.endAt) AS maxEnd " +
           "FROM WorkLog wl WHERE wl.workShift.id = :shiftId AND wl.isActive = true")
    ActiveLogBounds findActiveBoundsForShift(@Param("shiftId") Long shiftId);

    interface ActiveLogBounds {
        OffsetDateTime getMinStart();
        OffsetDateTime getMaxEnd();
    }

    // ── The operation detail screen ─────────────────────────────────────────
    //
    // Three questions the operation's own page asks of the work that was done on
    // it: who did it most recently, how much of it landed on each production
    // order, and how it adds up month by month. All three read only ACTIVE,
    // non-archived logs — a deactivated log is work that was withdrawn, and
    // counting it would overstate what the factory produced.

    /** The most recent work recorded on this operation, newest first. */
    @Query(value = """
        SELECT wl.id AS workLogId,
               e.full_name AS employeeName,
               ws.work_date AS workDate,
               wl.start_at AS startAt,
               wl.end_at AS endAt,
               wl.duration_min AS durationMin,
               wl.quantity AS quantity,
               wl.scrap AS scrap,
               po.id AS orderId,
               po.code AS orderCode
        FROM work_logs wl
        JOIN work_shifts ws ON ws.id = wl.work_shift_id
        JOIN employees e ON e.id = ws.employee_id
        LEFT JOIN production_orders po ON po.id = wl.production_order_id
        WHERE wl.operation_id = :operationId
          AND wl.is_active = true
          AND wl.archived_at IS NULL
        ORDER BY ws.work_date DESC, wl.id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<OperationWorkLogProjection> findRecentWorkForOperation(
            @Param("operationId") Long operationId,
            @Param("limit") int limit
    );

    /** Pieces and scrap recorded per production order for this operation. */
    @Query(value = """
        SELECT wl.production_order_id AS orderId,
               COALESCE(SUM(wl.quantity), 0) AS quantity,
               COALESCE(SUM(wl.scrap), 0) AS scrap
        FROM work_logs wl
        WHERE wl.operation_id = :operationId
          AND wl.production_order_id IS NOT NULL
          AND wl.is_active = true
          AND wl.archived_at IS NULL
        GROUP BY wl.production_order_id
        """, nativeQuery = true)
    List<OperationOrderOutputProjection> sumOutputPerOrderForOperation(@Param("operationId") Long operationId);

    /**
     * Month-by-month output for this operation, from the first day of the month
     * `:months` back through today. Months with no work are absent — the caller
     * fills the gaps, because a missing month must read as zero on a chart, not
     * as a break in the line.
     */
    @Query(value = """
        SELECT to_char(date_trunc('month', ws.work_date), 'YYYY-MM') AS period,
               COALESCE(SUM(wl.quantity), 0) AS quantity,
               COALESCE(SUM(wl.scrap), 0) AS scrap
        FROM work_logs wl
        JOIN work_shifts ws ON ws.id = wl.work_shift_id
        WHERE wl.operation_id = :operationId
          AND wl.is_active = true
          AND wl.archived_at IS NULL
          AND ws.work_date >= date_trunc('month', CURRENT_DATE) - make_interval(months => :months - 1)
        GROUP BY date_trunc('month', ws.work_date)
        ORDER BY date_trunc('month', ws.work_date)
        """, nativeQuery = true)
    List<OperationPeriodOutputProjection> monthlyOutputForOperation(
            @Param("operationId") Long operationId,
            @Param("months") int months
    );

    /**
     * Day-by-day output for this operation over the last `:days` days. Same
     * shape as the monthly query, and the same rule about gaps: absent days are
     * filled by the caller so a day with no work reads as zero, not as a break.
     */
    @Query(value = """
        SELECT to_char(ws.work_date, 'YYYY-MM-DD') AS period,
               COALESCE(SUM(wl.quantity), 0) AS quantity,
               COALESCE(SUM(wl.scrap), 0) AS scrap
        FROM work_logs wl
        JOIN work_shifts ws ON ws.id = wl.work_shift_id
        WHERE wl.operation_id = :operationId
          AND wl.is_active = true
          AND wl.archived_at IS NULL
          AND ws.work_date >= CURRENT_DATE - make_interval(days => :days - 1)
        GROUP BY ws.work_date
        ORDER BY ws.work_date
        """, nativeQuery = true)
    List<OperationPeriodOutputProjection> dailyOutputForOperation(
            @Param("operationId") Long operationId,
            @Param("days") int days
    );

    /**
     * `start_at`/`end_at` are `timestamptz`, and a NATIVE query hands those to
     * Spring as {@link java.time.Instant} — declaring them as OffsetDateTime here
     * fails at runtime with "Cannot project java.time.Instant to
     * java.time.OffsetDateTime". The moment is the same either way; the caller
     * gives it an offset.
     */
    interface OperationWorkLogProjection {
        Long getWorkLogId();
        String getEmployeeName();
        java.time.LocalDate getWorkDate();
        java.time.Instant getStartAt();
        java.time.Instant getEndAt();
        Integer getDurationMin();
        Integer getQuantity();
        Integer getScrap();
        Long getOrderId();
        String getOrderCode();
    }

    interface OperationOrderOutputProjection {
        Long getOrderId();
        Long getQuantity();
        Long getScrap();
    }

    interface OperationPeriodOutputProjection {
        String getPeriod();
        Long getQuantity();
        Long getScrap();
    }
}
