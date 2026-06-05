package com.aleksandarparipovic.marel_app.summary;

import com.aleksandarparipovic.marel_app.summary.dto.DailySummaryDto;
import com.aleksandarparipovic.marel_app.summary.dto.DailySummaryProjection;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final WorkLogRepository workLogRepo;

    /**
     * Fast daily summary read directly from work_logs (no derived tables needed).
     * Used immediately after a work-log mutation so the UI refreshes instantly.
     */
    @Transactional(readOnly = true)
    public DailySummaryDto getDailySummary(Long workShiftId) {
        DailySummaryProjection proj = workLogRepo.getDailySummaryByShift(workShiftId);

        List<WorkLogDto> logs = workLogRepo.getAllActiveLogsForShift(workShiftId);

        if (proj == null) {
            return DailySummaryDto.builder()
                    .workShiftId(workShiftId)
                    .totalShiftMinutes(0)
                    .totalWorkMinutes(0)
                    .totalQuantity(0)
                    .totalScrap(0)
                    .logs(logs)
                    .build();
        }

        return DailySummaryDto.builder()
                .workShiftId(proj.getWorkShiftId())
                .employeeId(proj.getEmployeeId())
                .workDate(proj.getWorkDate())
                .totalShiftMinutes(proj.getTotalShiftMinutes())
                .totalWorkMinutes(proj.getTotalWorkMinutes() != null ? proj.getTotalWorkMinutes() : 0L)
                .totalQuantity(proj.getTotalQuantity() != null ? proj.getTotalQuantity() : 0L)
                .totalScrap(proj.getTotalScrap() != null ? proj.getTotalScrap() : 0L)
                .logs(logs)
                .build();
    }
}
