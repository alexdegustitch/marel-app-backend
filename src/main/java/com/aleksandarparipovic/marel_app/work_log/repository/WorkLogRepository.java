package com.aleksandarparipovic.marel_app.work_log.repository;


import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogPreviewDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkLogRepository extends JpaRepository<WorkLog, Long>, JpaSpecificationExecutor<WorkLog> {

    @Query(value = """
    SELECT
        wl.id AS id,
        wl.work_shift_id AS shiftId,
        o.id AS operationId,
        o.op_name AS operationName,
        po.id AS productionOrderId,
        po.name AS productionOrderName,
        p.id AS productId,
        p.product_name AS productName,
        wl.start_at AS startAt,
        wl.end_at AS endAt,
        wl.duration_min AS durationMin,
        wl.quantity AS quantity,
        wl.scrap AS scrap,
        wl.note AS note,
        wl.hourly_output AS hourlyOutput,
        wl.work_code_id as workCodeCategoryId,
        wl.is_active as isActive
    FROM work_logs wl
    LEFT JOIN operations o ON wl.operation_id = o.id
    LEFT JOIN production_orders po ON wl.production_order_id = po.id
    LEFT JOIN products p ON o.product_id = p.id
    WHERE wl.work_shift_id IN (:shiftIds)
    AND wl.is_active = true
    ORDER BY wl.work_shift_id, wl.start_at
    """, nativeQuery = true)
    List<WorkLogDto> getLogsForShifts(List<Long> shiftIds);

    @Query(value = """
    SELECT
        wl.id AS id,
        wl.work_shift_id AS shiftId,
        o.id AS operationId,
        o.op_name AS operationName,
        po.id AS productionOrderId,
        po.name AS productionOrderName,
        p.id AS productId,
        p.product_name AS productName,
        wl.start_at AS startAt,
        wl.end_at AS endAt,
        wl.duration_min AS durationMin,
        wl.quantity AS quantity,
        wl.scrap AS scrap,
        wl.note AS note,
        wl.hourly_output AS hourlyOutput,
        wl.work_code_id as workCodeCategoryId,
        wl.is_active as isActive
    FROM work_logs wl
    LEFT JOIN operations o ON wl.operation_id = o.id
    LEFT JOIN production_orders po ON wl.production_order_id = po.id
    LEFT JOIN products p ON o.product_id = p.id
    WHERE wl.work_shift_id = :shiftId
    AND wl.is_active = true
    ORDER BY wl.work_shift_id, wl.start_at
    """, nativeQuery = true)
    List<WorkLogDto> getAllActiveLogsForShift(Long shiftId);

    @Query(value = """
    SELECT
        wl.id AS id,
        wl.work_shift_id AS shiftId,
        o.id AS operationId,
        o.op_name AS operationName,
        wl.start_at AS startAt,
        wl.end_at AS endAt,
        wl.duration_min AS durationMin
    FROM work_logs wl
    LEFT JOIN operations o ON wl.operation_id = o.id
    WHERE wl.work_shift_id IN (:shiftIds)
    AND wl.is_active = true
    ORDER BY wl.work_shift_id, wl.start_at, wl.end_at
    """, nativeQuery = true)
    List<WorkLogPreviewDto> getLogsPreviewForShifts(List<Long> shiftIds);
}
