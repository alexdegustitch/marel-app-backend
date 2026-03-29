package com.aleksandarparipovic.marel_app.monthly_report_category;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/monthly-report-categories")
@RequiredArgsConstructor
public class MonthlyReportCategoryController {

    private final MonthlyReportCategoryService monthlyReportCategoryService;

    @GetMapping
    public ResponseEntity<List<MonthlyReportCategory>> findAll() {
        return ResponseEntity.ok(monthlyReportCategoryService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MonthlyReportCategory> findById(@PathVariable Long id) {
        return ResponseEntity.ok(monthlyReportCategoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<MonthlyReportCategory> create(@RequestBody MonthlyReportCategory entity) {
        return ResponseEntity.ok(monthlyReportCategoryService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MonthlyReportCategory> update(@PathVariable Long id, @RequestBody MonthlyReportCategory entity) {
        return ResponseEntity.ok(monthlyReportCategoryService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        monthlyReportCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
