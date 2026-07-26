package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import com.aleksandarparipovic.marel_app.bonus_calendar_sync.BonusCalendarSyncService;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleResponse;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRulesByYearDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bonus-min-hours-rules")
@RequiredArgsConstructor
public class BonusMinHoursRuleController {

    private final BonusMinHoursRuleService service;
    private final BonusCalendarSyncService bonusCalendarSyncService;

    @PostMapping("/init-year")
    public ResponseEntity<List<BonusMinHoursRuleResponse>> initYear(@RequestParam int year) {
        return ResponseEntity.ok(bonusCalendarSyncService.initYear(year));
    }

    @GetMapping
    public ResponseEntity<List<BonusMinHoursRuleResponse>> findAllActive() {
        return ResponseEntity.ok(service.findAllActive());
    }

    @GetMapping("/grouped-by-year")
    public ResponseEntity<List<BonusMinHoursRulesByYearDto>> findGroupedByYear() {
        return ResponseEntity.ok(service.findGroupedByYear());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BonusMinHoursRuleResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<BonusMinHoursRuleResponse> create(@Valid @RequestBody BonusMinHoursRuleRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BonusMinHoursRuleResponse> update(@PathVariable Long id,
                                                             @Valid @RequestBody BonusMinHoursRuleRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
