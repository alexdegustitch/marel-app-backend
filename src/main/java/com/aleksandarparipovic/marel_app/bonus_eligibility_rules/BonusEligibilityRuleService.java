package com.aleksandarparipovic.marel_app.bonus_eligibility_rules;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BonusEligibilityRuleService {

    private final BonusEligibilityRuleRepository repository;

    @Transactional(readOnly = true)
    public List<BonusEligibilityRuleResponse> findAllActive() {
        return repository.findByArchivedAtIsNullOrderByPeriodDesc().stream()
                .map(BonusEligibilityRuleResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public BonusEligibilityRuleResponse findById(Long id) {
        return repository.findByIdAndArchivedAtIsNull(id)
                .map(BonusEligibilityRuleResponse::new)
                .orElseThrow(() -> new IllegalArgumentException("Bonus eligibility rule not found: " + id));
    }

    @Transactional
    public BonusEligibilityRuleResponse create(BonusEligibilityRuleRequest request) {
        BonusEligibilityRule entity = new BonusEligibilityRule();
        applyRequest(entity, request);

        if (repository.existsByPeriodAndArchivedAtIsNull(entity.getPeriod())) {
            throw new IllegalArgumentException("Active bonus eligibility rule already exists for period: " + entity.getPeriod());
        }

        return new BonusEligibilityRuleResponse(repository.save(entity));
    }

    @Transactional
    public BonusEligibilityRuleResponse update(Long id, BonusEligibilityRuleRequest request) {
        BonusEligibilityRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("Bonus eligibility rule not found: " + id));

        applyRequest(entity, request);

        if (repository.existsByPeriodAndArchivedAtIsNullAndIdNot(entity.getPeriod(), id)) {
            throw new IllegalArgumentException("Active bonus eligibility rule already exists for period: " + entity.getPeriod());
        }

        return new BonusEligibilityRuleResponse(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        BonusEligibilityRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("Bonus eligibility rule not found: " + id));
        entity.setArchivedAt(OffsetDateTime.now());
        repository.save(entity);
    }

    private void applyRequest(BonusEligibilityRule entity, BonusEligibilityRuleRequest request) {
        // Normalize period to first day of month to satisfy DB month-level constraint.
        entity.setPeriod(YearMonth.from(request.getPeriod()).atDay(1));
        entity.setMinNumHours(request.getMinNumHours());
        entity.setSaturdayCount(request.getSaturdayCount());
        entity.setBonusValue(request.getBonusValue());
        entity.setNote(request.getNote());
    }
}

