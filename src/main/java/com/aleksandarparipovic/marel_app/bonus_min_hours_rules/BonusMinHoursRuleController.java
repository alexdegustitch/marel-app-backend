package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import com.aleksandarparipovic.marel_app.bonus_calendar_sync.BonusCalendarSyncService;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursManualRequest;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleHistoryDto;
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

    /**
     * Sets a month's minimum by hand. The calendar's own answer is kept beside it and goes on
     * being maintained, so this decides which of the two applies rather than replacing one.
     */
    @PutMapping("/{id}/manual")
    public ResponseEntity<BonusMinHoursRuleResponse> setManual(
            @PathVariable Long id,
            @Valid @RequestBody BonusMinHoursManualRequest request) {
        return ResponseEntity.ok(service.setManual(id, request.getManualMinNumHours(), request.getNote()));
    }

    /** Drops the manual value, returning the month to the calendar's CURRENT answer. */
    @DeleteMapping("/{id}/manual")
    public ResponseEntity<BonusMinHoursRuleResponse> resetManual(@PathVariable Long id) {
        return ResponseEntity.ok(service.resetManual(id, null));
    }

    /** Every interval this month's minimum has been through, newest first. */
    @GetMapping("/{id}/history")
    public ResponseEntity<List<BonusMinHoursRuleHistoryDto>> history(@PathVariable Long id) {
        return ResponseEntity.ok(service.findHistory(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
