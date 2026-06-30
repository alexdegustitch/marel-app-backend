package com.aleksandarparipovic.marel_app.monthly_report;

import com.aleksandarparipovic.marel_app.monthly_report.dto.MonthlyReportByEmployeeRecordResponse;
import com.aleksandarparipovic.marel_app.monthly_report.dto.MonthlyReportCreateRequest;
import com.aleksandarparipovic.marel_app.monthly_report.dto.MonthlyReportCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/monthly-reports")
@RequiredArgsConstructor
public class MonthlyReportController {

    private final MonthlyReportService monthlyReportService;

    @GetMapping
    public ResponseEntity<List<MonthlyReport>> findAll() {
        return ResponseEntity.ok(monthlyReportService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MonthlyReport> findById(@PathVariable Long id) {
        return ResponseEntity.ok(monthlyReportService.findById(id));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<MonthlyReport>> findAllByEmployeeIdAndYearAndMonth(
            @PathVariable Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month
    ) {
        return ResponseEntity.ok(monthlyReportService.findAllByEmployeeIdAndYearAndMonth(employeeId, year, month));
    }

    @GetMapping("/employee-record/{employeeRecordId}")
    public ResponseEntity<MonthlyReportByEmployeeRecordResponse> findByEmployeeRecordId(@PathVariable Long employeeRecordId) {
        return ResponseEntity.ok(monthlyReportService.findByEmployeeRecordId(employeeRecordId));
    }

    @GetMapping("/employee-record/{employeeRecordId}/previous-month")
    public ResponseEntity<MonthlyReportByEmployeeRecordResponse> findPreviousMonthByEmployeeRecordId(@PathVariable Long employeeRecordId) {
        return monthlyReportService.findPreviousMonthByEmployeeRecordId(employeeRecordId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping({"", "/create"})
    public ResponseEntity<MonthlyReportCreateResponse> create(@Valid @RequestBody MonthlyReportCreateRequest request) {
        return ResponseEntity.ok(monthlyReportService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MonthlyReport> update(@PathVariable Long id, @RequestBody MonthlyReport entity) {
        return ResponseEntity.ok(monthlyReportService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        monthlyReportService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
