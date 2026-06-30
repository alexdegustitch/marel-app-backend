package com.aleksandarparipovic.marel_app.daily_report;

import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateRequest;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateResponse;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportChartInfo;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportDto;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportEmployeeMonthlyInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/daily-reports")
@RequiredArgsConstructor
public class DailyReportController {

    private final DailyReportService dailyReportService;

    @GetMapping
    public ResponseEntity<List<DailyReport>> findAll() {
        return ResponseEntity.ok(dailyReportService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DailyReport> findById(@PathVariable Long id) {
        return ResponseEntity.ok(dailyReportService.findById(id));
    }

    @GetMapping("/employee/{employeeId}/chart-info")
    public ResponseEntity<List<DailyReportChartInfo>> findChartInfoByEmployeeAndPeriod(
            @PathVariable Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month
    ) {
        return ResponseEntity.ok(dailyReportService.findChartInfoByEmployeeAndPeriod(employeeId, year, month));
    }

    @GetMapping("/employee/{employeeId}/monthly-info")
    public ResponseEntity<List<DailyReportEmployeeMonthlyInfo>> findEmployeeMonthlyInfo(
            @PathVariable Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month
    ) {
        return ResponseEntity.ok(dailyReportService.findEmployeeMonthlyInfo(employeeId, year, month));
    }

    @GetMapping("/work-shift/{workShiftId}")
    public ResponseEntity<DailyReportDto> findByWorkShiftId(@PathVariable Long workShiftId){
        return  ResponseEntity.ok(dailyReportService.findByWorkShiftId(workShiftId));
    }

    @PostMapping({"", "/create"})
    public ResponseEntity<DailyReportCreateResponse> create(@Valid @RequestBody DailyReportCreateRequest request) {
        return ResponseEntity.ok(dailyReportService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DailyReport> update(@PathVariable Long id, @RequestBody DailyReport entity) {
        return ResponseEntity.ok(dailyReportService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        dailyReportService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
