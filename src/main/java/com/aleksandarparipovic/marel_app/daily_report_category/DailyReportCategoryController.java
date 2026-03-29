package com.aleksandarparipovic.marel_app.daily_report_category;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/daily-report-categories")
@RequiredArgsConstructor
public class DailyReportCategoryController {

    private final DailyReportCategoryService dailyReportCategoryService;

    @GetMapping
    public ResponseEntity<List<DailyReportCategory>> findAll() {
        return ResponseEntity.ok(dailyReportCategoryService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DailyReportCategory> findById(@PathVariable Long id) {
        return ResponseEntity.ok(dailyReportCategoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<DailyReportCategory> create(@RequestBody DailyReportCategory entity) {
        return ResponseEntity.ok(dailyReportCategoryService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DailyReportCategory> update(@PathVariable Long id, @RequestBody DailyReportCategory entity) {
        return ResponseEntity.ok(dailyReportCategoryService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        dailyReportCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
