package com.aleksandarparipovic.marel_app.bonus_eligibility_rules;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleBulkUpdateRequest;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleResponse;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRulesByYearDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bonus-eligibility-rules")
@RequiredArgsConstructor
public class BonusEligibilityRuleController {

    private final BonusEligibilityRuleService service;

    @GetMapping("/grouped-by-year")
    public ResponseEntity<List<BonusEligibilityRulesByYearDto>> findGroupedByYear() {
        return ResponseEntity.ok(service.findGroupedByYear());
    }

    @PostMapping("/initialize")
    public ResponseEntity<List<BonusEligibilityRuleResponse>> initializeForMonth(
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ResponseEntity.ok(service.initializeForMonth(year, month));
    }

    @PutMapping("/bulk-update")
    public ResponseEntity<List<BonusEligibilityRuleResponse>> bulkUpdate(
            @Valid @RequestBody BonusEligibilityRuleBulkUpdateRequest request) {
        return ResponseEntity.ok(service.bulkUpdate(request));
    }

    @GetMapping
    public ResponseEntity<List<BonusEligibilityRuleResponse>> findAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        if (period != null) {
            return ResponseEntity.ok(service.findByPeriod(period));
        }
        return ResponseEntity.ok(service.findAllActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BonusEligibilityRuleResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<BonusEligibilityRuleResponse> create(@Valid @RequestBody BonusEligibilityRuleRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BonusEligibilityRuleResponse> update(@PathVariable Long id,
                                                               @Valid @RequestBody BonusEligibilityRuleRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
