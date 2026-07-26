package com.aleksandarparipovic.marel_app.summary.dto;

import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class DailySummaryDto {
    private Long workShiftId;
    private Long employeeId;
    private LocalDate workDate;
    private Integer totalShiftMinutes;
    private long totalMinutes;
    private BigDecimal performanceRate;
    private BigDecimal approvedPerformanceRate;
    private BigDecimal performanceCoefficient;
    private BigDecimal totalWeightedNormMinutes;
    /**
     * Coefficient-weighted verified time: the sum over non-overlapping covered
     * intervals of interval duration × the PL/PLB coefficient in force. A separate
     * quantity from {@code totalWeightedNormMinutes}, which stays efficiency-weighted.
     */
    private BigDecimal verifiedMinutes;
    /** Covered minutes classified PL (below the PLB concurrency threshold). */
    private long plMinutes;
    /** Covered minutes classified PLB (three or more parallel-capable logs active). */
    private long plbMinutes;
    /** Ids of logs that overlap a log from a different work-code category — flagged, not auto-merged. */
    private List<Long> overlappingLogIds;
    /** Lightweight log timeline entries for chart rendering. */
    private List<WorkLogDto> logs;
}
