package com.aleksandarparipovic.marel_app.work_log.interval;

import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Adapts persisted work logs to the interval engine and resolves the PLB
 * coefficient from configuration.
 *
 * <p>Both the fast read path and the recalc engine go through here, so they can
 * never disagree about which logs are parallel-capable or what a PLB minute is
 * worth.
 */
@Component
@RequiredArgsConstructor
public class ShiftIntervalResolver {

    /**
     * The mapping whose target category is PLB. This is how PLB is identified —
     * by configuration, never by matching a category name or the literal
     * string "PLB".
     */
    public static final String MULTIPLE_MACHINES_BONUS = "MULTIPLE_MACHINES_BONUS";

    private final WorkCodeCategoryMappingRepository mappingRepository;

    /**
     * Convert active work logs into interval inputs.
     *
     * <p>Parallel capability and the coefficient both come from the log's own
     * work-code category. The original {@code workCode} is used rather than
     * {@code effectiveWorkCode}: the night/weekend remap is a bonus-pay concern
     * and must not change which operations can run in parallel.
     */
    public List<WorkIntervalInput> toIntervals(Collection<WorkLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        return logs.stream()
                .filter(log -> log.getWorkCode() != null)
                .map(log -> new WorkIntervalInput(
                        log.getId(),
                        log.getStartAt(),
                        log.getEndAt(),
                        Boolean.TRUE.equals(log.getWorkCode().getAllowsParallelWork()),
                        multiplierOf(log.getWorkCode())))
                .toList();
    }

    /**
     * The PLB coefficient in force on {@code workDate}, or null when no PLB
     * mapping is configured for that date.
     *
     * <p>Resolved by work date rather than "now", so recalculating an old day
     * uses the coefficient that was valid when the work happened.
     */
    @Transactional(readOnly = true)
    public BigDecimal resolvePlbCoefficient(LocalDate workDate) {
        if (workDate == null) {
            return null;
        }
        List<WorkCodeCategoryMapping> mappings =
                mappingRepository.findActiveByTypesAndDate(Set.of(MULTIPLE_MACHINES_BONUS), workDate);

        return mappings.stream()
                .map(WorkCodeCategoryMapping::getTargetCategory)
                .filter(java.util.Objects::nonNull)
                .map(ShiftIntervalResolver::multiplierOf)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal multiplierOf(WorkCodeCategory category) {
        if (category == null || category.getNormMultiplier() == null) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(category.getNormMultiplier());
    }
}
