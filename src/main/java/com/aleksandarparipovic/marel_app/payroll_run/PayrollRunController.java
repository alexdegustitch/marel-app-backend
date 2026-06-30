package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunResponse;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSummaryDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-runs")
@RequiredArgsConstructor
public class PayrollRunController {

    private final PayrollRunService payrollRunService;

    /** GET /api/payroll-runs/year/{year} — summaries per employee for entire year */
    @GetMapping("/year/{year}")
    public ResponseEntity<List<PayrollRunSummaryDto>> getSummariesByYear(@PathVariable int year) {
        return ResponseEntity.ok(payrollRunService.getSummariesByYear(year));
    }

    /** GET /api/payroll-runs/last-activity?year=&month= — last 3 items touched by current user */
    @GetMapping("/last-activity")
    public ResponseEntity<List<PayrollRunSummaryDto>> getLastActivity(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(payrollRunService.getLastActivity(year, month));
    }

    /** GET /api/payroll-runs?year=&month=&page=&size=&globalSearch=&sort=&status= — paged per-employee list */
    @GetMapping(params = {"year", "month"})
    public ResponseEntity<Page<PayrollRunInfoDto>> getPagedByYearAndMonth(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String globalSearch,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return ResponseEntity.ok(payrollRunService.getPagedByYearAndMonth(year, month, globalSearch, status, pageable));
    }

    @GetMapping
    public ResponseEntity<List<PayrollRunResponse>> findAll() {
        return ResponseEntity.ok(payrollRunService.findAll().stream().map(PayrollRunResponse::new).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<PayrollRunResponse> create(@Valid @RequestBody PayrollRunCreateRequest request) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunResponse> update(@PathVariable Long id, @RequestBody PayrollRun entity) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.update(id, entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
