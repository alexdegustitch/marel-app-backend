package com.aleksandarparipovic.marel_app.bonus_eligibility_rules;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bonus-eligibility-rules")
@RequiredArgsConstructor
public class BonusEligibilityRuleController {

    private final BonusEligibilityRuleService service;

    @GetMapping
    public ResponseEntity<List<BonusEligibilityRuleResponse>> findAll() {
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

