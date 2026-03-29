package com.aleksandarparipovic.marel_app.monthly_report;

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

    @PostMapping
    public ResponseEntity<MonthlyReport> create(@RequestBody MonthlyReport entity) {
        return ResponseEntity.ok(monthlyReportService.create(entity));
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
