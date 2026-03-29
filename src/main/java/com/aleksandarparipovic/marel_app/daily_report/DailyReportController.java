package com.aleksandarparipovic.marel_app.daily_report;

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

    @PostMapping
    public ResponseEntity<DailyReport> create(@RequestBody DailyReport entity) {
        return ResponseEntity.ok(dailyReportService.create(entity));
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
