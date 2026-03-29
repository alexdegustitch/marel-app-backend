package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.utils.dates.DateUtil;
import com.aleksandarparipovic.marel_app.utils.dto.StartEndResult;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDtoImpl;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogFormDto;
import com.aleksandarparipovic.marel_app.work_log.validation.WorkLogValidator;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@RequiredArgsConstructor
@Component
public class WorkLogMapper {

    private final WorkLogValidator workLogValidator;
    private final EntityReferenceProvider referenceProvider;
    private final DateUtil dateUtil;

    public WorkLogDto toDto(WorkLog workLog) {
        Operation operation = workLog.getOperation();
        ProductionOrder productionOrder = workLog.getProductionOrder();

        Long productId = null;
        String productName = null;
        if (operation != null && operation.getProduct() != null) {
            productId = operation.getProduct().getId();
            productName = operation.getProduct().getProductName();
        }

        return new WorkLogDtoImpl(
                workLog.getId(),
                workLog.getWorkShift() == null ? null : workLog.getWorkShift().getId(),
                operation == null ? null : operation.getId(),
                operation == null ? null : operation.getOpName(),
                productionOrder == null ? null : productionOrder.getId(),
                productionOrder == null ? null : productionOrder.getName(),
                productId,
                productName,
                toInstant(workLog.getStartAt()),
                toInstant(workLog.getEndAt()),
                workLog.getDurationMin(),
                workLog.getQuantity(),
                workLog.getScrap(),
                workLog.getNote(),
                workLog.getHourlyOutput(),
                workLog.getWorkCode() == null ? null : workLog.getWorkCode().getId(),
                workLog.getIsActive()
        );
    }

    private Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public WorkLog toEntity(WorkLogFormDto dto) {
        Operation operation = referenceProvider.getOptionalReference(Operation.class, dto.getOperationId());
        // validateOperationProductConsistency(dto.getProductId(), operation);

        WorkShift workShift = referenceProvider.getRequiredReference(WorkShift.class, dto.getWorkShiftId(), "workShiftId");
        // OffsetDateTime startAt = dateUtil.parseOffsetDateTime(dto.getStartAt(), "startAt");
        // OffsetDateTime endAt = dateUtil.parseOffsetDateTime(dto.getEndAt(), "endAt");
        ZoneId zone = ZoneId.of("Europe/Belgrade");
        StartEndResult time = dateUtil.buildStartEnd(workShift.getWorkDate(), dto.getStartAt(), dto.getEndAt(), workShift.getStartAt().toLocalTime(), zone);

        workLogValidator.validateTimeRange(time.start(), time.end());

        return WorkLog.builder()
                .workShift(workShift)
                .productionOrder(referenceProvider.getOptionalReference(ProductionOrder.class, dto.getProductionOrderId()))
                .operation(operation)
                .workCode(referenceProvider.getOptionalReference(WorkCodeCategory.class, dto.getWorkCodeCategoryId()))
                .startAt(time.start())
                .endAt(time.end())
                .scrap(defaultInt(dto.getScrap()))
                .quantity(defaultInt(dto.getQuantity()))
                .note(dto.getNote())
                .isActive(dto.getIsActive())
                .build();
    }

    public void updateEntity(WorkLog entity, WorkLogFormDto dto) {
        if (dto.getWorkShiftId() != null) {
            entity.setWorkShift(referenceProvider.getRequiredReference(WorkShift.class, dto.getWorkShiftId(), "workShiftId"));
        }
        entity.setProductionOrder(referenceProvider.getOptionalReference(ProductionOrder.class, dto.getProductionOrderId()));

        Operation operation = referenceProvider.getOptionalReference(Operation.class, dto.getOperationId());
        //validateOperationProductConsistency(dto.getProductId(), operation);
        entity.setOperation(operation);

        entity.setWorkCode(referenceProvider.getOptionalReference(WorkCodeCategory.class, dto.getWorkCodeCategoryId()));

        //WorkShift workShift = referenceProvider.getRequiredReference(WorkShift.class, dto.getWorkShiftId(), "workShiftId");

        ZoneId zone = ZoneId.of("Europe/Belgrade");
        StartEndResult time = dateUtil.buildStartEnd(entity.getWorkShift().getWorkDate(), dto.getStartAt(), dto.getEndAt(), entity.getWorkShift().getStartAt().toLocalTime(), zone);

        workLogValidator.validateTimeRange(time.start(), time.end());
        entity.setStartAt(time.start());
        entity.setEndAt(time.end());

        entity.setScrap(defaultInt(dto.getScrap()));
        entity.setQuantity(defaultInt(dto.getQuantity()));
        entity.setNote(dto.getNote());
        if (dto.getIsActive() != null) {
            entity.setIsActive(dto.getIsActive());
        }
    }

    private Integer defaultInt(Integer value) {
        return value == null ? 0 : value;
    }


}
