package com.aleksandarparipovic.marel_app.summary.dto;

import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class DailySummaryDto {
    private Long workShiftId;
    private Long employeeId;
    private LocalDate workDate;
    private Integer totalShiftMinutes;
    private long totalWorkMinutes;
    private long totalQuantity;
    private long totalScrap;
    /** Lightweight log timeline entries for chart rendering. */
    private List<WorkLogDto> logs;
}
