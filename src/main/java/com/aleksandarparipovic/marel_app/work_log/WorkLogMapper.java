package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.utils.dates.DateUtil;
import com.aleksandarparipovic.marel_app.utils.dto.StartEndResult;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolution;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDtoImpl;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogFormDto;
import com.aleksandarparipovic.marel_app.work_log.validation.WorkLogValidator;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@RequiredArgsConstructor
@Component
public class WorkLogMapper {

    /** Matches work_logs.norm_multiplier_manual and the report rows' own column. */
    private static final int COEFFICIENT_SCALE = 2;

    private final WorkLogValidator workLogValidator;
    private final EntityReferenceProvider referenceProvider;
    private final DateUtil dateUtil;
    private final WorkLogCompensationSnapshot compensationSnapshot;
    private final CurrentUserService currentUserService;

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
                operation == null ? null : operation.getMinNorm(),
                productionOrder == null ? null : productionOrder.getId(),
                productionOrder == null ? null : productionOrder.getName(),
                productionOrder == null ? null : productionOrder.getCode(),
                productId,
                productName,
                workLog.getPerformanceRate(),
                workLog.getApprovedPerformanceRate(),
                toInstant(workLog.getStartAt()),
                toInstant(workLog.getEndAt()),
                workLog.getDurationMin(),
                workLog.getQuantity(),
                workLog.getScrap(),
                workLog.getNote(),
                workLog.getHourlyOutput(),
                workLog.getWorkCode() == null ? null : workLog.getWorkCode().getId(),
                workLog.getWorkCode() == null ? null : workLog.getWorkCode().getCategoryNo(),
                workLog.getEffectiveWorkCode() == null ? null : workLog.getEffectiveWorkCode().getId(),
                workLog.getEffectiveWorkCode() == null ? null : workLog.getEffectiveWorkCode().getCategoryNo(),
                workLog.getIsActive(),
                workLog.getNormMultiplierSnapshot(),
                workLog.getNormMultiplierManual(),
                workLog.getWorkCode() == null ? null : workLog.getWorkCode().getAllowsParallelWork()
        );
    }

    private Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Write, keep or clear the coefficient somebody typed over the resolved one.
     *
     * <p>Three cases, and the last two are why this is not a plain setter:
     *
     * <ul>
     *   <li><b>Nothing sent</b> — the override, if any, is removed and the log
     *       goes back to what the scheme resolved. That is how a supervisor takes
     *       it back: clear the field.</li>
     *   <li><b>The resolved value sent back</b> — not an override at all. Stored
     *       as none, so nothing claims to be "changed" when it matches the
     *       default, and typing the default is another way to undo.</li>
     *   <li><b>The same override sent back</b> — an open form round-tripping what
     *       it was given. The value stays and so do the name and the moment;
     *       rewriting them would credit the last person to press Save rather than
     *       the person who made the decision.</li>
     * </ul>
     */
    private void applyManualCoefficient(WorkLog log, WorkLogFormDto dto, WorkCategoryResolution resolution) {
        BigDecimal typed = dto.getNormMultiplierManual();
        BigDecimal scaled = typed == null ? null : typed.setScale(COEFFICIENT_SCALE, RoundingMode.HALF_UP);

        if (scaled != null && scaled.signum() < 0) {
            throw new IllegalArgumentException("Koeficijent kategorije ne može biti negativan.");
        }

        // Same as the default: that is the default, not a decision to depart from it.
        if (scaled != null && resolution != null && resolution.coefficient() != null
                && scaled.compareTo(resolution.coefficient().setScale(COEFFICIENT_SCALE, RoundingMode.HALF_UP)) == 0) {
            scaled = null;
        }

        BigDecimal current = log.getNormMultiplierManual();

        if (scaled == null) {
            if (current == null) {
                return;
            }
            log.setNormMultiplierManual(null);
            log.setNormMultiplierManualBy(null);
            log.setNormMultiplierManualAt(null);
            return;
        }

        if (current != null && current.compareTo(scaled) == 0) {
            return;
        }

        Long authorId = currentUserService.getCurrentUserId();
        if (authorId == null) {
            throw new IllegalStateException(
                    "Koeficijent može ručno da izmeni samo prijavljeni korisnik.");
        }

        log.setNormMultiplierManual(scaled);
        log.setNormMultiplierManualBy(referenceProvider.getRequiredReference(
                User.class, authorId, "normMultiplierManualBy"));
        log.setNormMultiplierManualAt(OffsetDateTime.now());
    }

    /**
     * @param resolution the already-validated compensation-scheme resolution for
     *                   this log's employee, work date and source category. It is
     *                   passed in rather than resolved here so a batch of logs on
     *                   the same shift shares one resolution context instead of
     *                   issuing two queries per log.
     */
    public WorkLog toEntity(WorkLogFormDto dto, WorkCategoryResolution resolution) {
        Operation operation = referenceProvider.getRequiredReference(Operation.class, dto.getOperationId(), "operationId");
        // validateOperationProductConsistency(dto.getProductId(), operation);

        WorkShift workShift = referenceProvider.getRequiredReference(WorkShift.class, dto.getWorkShiftId(), "workShiftId");
        // OffsetDateTime startAt = dateUtil.parseOffsetDateTime(dto.getStartAt(), "startAt");
        // OffsetDateTime endAt = dateUtil.parseOffsetDateTime(dto.getEndAt(), "endAt");
        ZoneId zone = ZoneId.of("Europe/Belgrade");
        StartEndResult time = dateUtil.buildStartEnd(workShift.getWorkDate(), dto.getStartAt(), dto.getEndAt(), workShift.getStartAt().toLocalTime(), zone);

        workLogValidator.validateTimeRange(time.start(), time.end());

        WorkLog workLog = WorkLog.builder()
                .workShift(workShift)
                .productionOrder(referenceProvider.getOptionalReference(ProductionOrder.class, dto.getProductionOrderId()))
                .operation(operation)
                .workCode(referenceProvider.getRequiredReference(WorkCodeCategory.class, dto.getWorkCodeCategoryId(), "workCodeCategoryId"))
                .startAt(time.start())
                .endAt(time.end())
                .scrap(dto.getScrap())
                .quantity(dto.getQuantity())
                .note(dto.getNote())
                .isActive(dto.getIsActive())
                .performanceRate(dto.getPerformanceRate())
                .approvedPerformanceRate(dto.getApprovedPerformanceRate())
                .build();

        // The RESOLVED coefficient and the effective category come from the
        // resolver, never from the request body: a client must not be able to
        // quietly choose what its own work is worth.
        compensationSnapshot.apply(workLog, resolution);

        // A coefficient typed on purpose is the one exception, and it is not
        // quiet: it lands in its own column, beside the resolved value it
        // replaced, with a name and a moment attached.
        applyManualCoefficient(workLog, dto, resolution);

        return workLog;
    }

    public void updateEntity(WorkLog entity, WorkLogFormDto dto, WorkCategoryResolution resolution) {
        if (dto.getWorkShiftId() != null) {
            entity.setWorkShift(referenceProvider.getRequiredReference(WorkShift.class, dto.getWorkShiftId(), "workShiftId"));
        }
        entity.setProductionOrder(referenceProvider.getOptionalReference(ProductionOrder.class, dto.getProductionOrderId()));

        Operation operation = referenceProvider.getRequiredReference(Operation.class, dto.getOperationId(), "operationId");
        //validateOperationProductConsistency(dto.getProductId(), operation);
        entity.setOperation(operation);

        entity.setWorkCode(referenceProvider.getRequiredReference(WorkCodeCategory.class, dto.getWorkCodeCategoryId(), "workCodeCategoryId"));

        //WorkShift workShift = referenceProvider.getRequiredReference(WorkShift.class, dto.getWorkShiftId(), "workShiftId");

        ZoneId zone = ZoneId.of("Europe/Belgrade");
        StartEndResult time = dateUtil.buildStartEnd(entity.getWorkShift().getWorkDate(), dto.getStartAt(), dto.getEndAt(), entity.getWorkShift().getStartAt().toLocalTime(), zone);

        workLogValidator.validateTimeRange(time.start(), time.end());
        entity.setStartAt(time.start());
        entity.setEndAt(time.end());

        entity.setScrap(dto.getScrap());
        entity.setQuantity(dto.getQuantity());
        entity.setNote(dto.getNote());
        entity.setPerformanceRate(dto.getPerformanceRate());
        entity.setApprovedPerformanceRate(dto.getApprovedPerformanceRate());

        // Re-resolved from the employee's scheme for the work date, deliberately
        // ignoring dto.normMultiplierSnapshot. The DTO value used to be written
        // straight through, which meant a client could post any coefficient it
        // liked and a stale value round-tripped from an open form could overwrite
        // a correct one.
        compensationSnapshot.apply(entity, resolution);
        applyManualCoefficient(entity, dto, resolution);

        if (dto.getIsActive() != null) {
            entity.setIsActive(dto.getIsActive());
        }
    }


}
