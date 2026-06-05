package com.aleksandarparipovic.marel_app.summary;

import com.aleksandarparipovic.marel_app.summary.dto.DailySummaryDto;
import com.aleksandarparipovic.marel_app.summary.dto.MonthlySummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/summary")
@RequiredArgsConstructor
public class ReportSummaryController {

    private final DailySummaryService dailySummaryService;
    private final MonthlySummaryService monthlySummaryService;

    /**
     * Fast daily summary keyed by work-shift ID.
     * Reads directly from work_logs – safe to call immediately after a work-log mutation.
     *
     * GET /api/reports/summary/daily?workShiftId=123
     */
    @GetMapping("/daily")
    public ResponseEntity<DailySummaryDto> getDailySummary(@RequestParam Long workShiftId) {
        return ResponseEntity.ok(dailySummaryService.getDailySummary(workShiftId));
    }

    /**
     * Monthly summary keyed by employee / year / month.
     * Reads from daily_reports (may be briefly stale right after a mutation).
     *
     * GET /api/reports/summary/monthly?employeeId=1&year=2026&month=3
     */
    @GetMapping("/monthly")
    public ResponseEntity<MonthlySummaryDto> getMonthlySummary(
            @RequestParam Long employeeId,
            @RequestParam int year,
            @RequestParam int month
    ) {
        return ResponseEntity.ok(monthlySummaryService.getMonthlySummary(employeeId, year, month));
    }
}
