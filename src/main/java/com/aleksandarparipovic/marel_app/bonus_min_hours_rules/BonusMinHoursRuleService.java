package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleResponse;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRulesByYearDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BonusMinHoursRuleService {

    private final BonusMinHoursRuleRepository repository;

    @Transactional(readOnly = true)
    public List<BonusMinHoursRuleResponse> findAllActive() {
        return repository.findByArchivedAtIsNullOrderByPeriodDesc().stream()
                .map(BonusMinHoursRuleResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public BonusMinHoursRuleResponse findById(Long id) {
        return repository.findByIdAndArchivedAtIsNull(id)
                .map(BonusMinHoursRuleResponse::new)
                .orElseThrow(() -> new IllegalArgumentException("BonusMinHoursRule not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<BonusMinHoursRulesByYearDto> findGroupedByYear() {
        List<BonusMinHoursRule> all = repository.findByArchivedAtIsNullOrderByPeriodDesc();

        Map<Integer, List<BonusMinHoursRuleResponse>> byYear = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getPeriod().getYear(),
                        Collectors.mapping(BonusMinHoursRuleResponse::new, Collectors.toList())
                ));

        return byYear.entrySet().stream()
                .sorted(Map.Entry.<Integer, List<BonusMinHoursRuleResponse>>comparingByKey().reversed())
                .map(e -> new BonusMinHoursRulesByYearDto(e.getKey(), e.getValue()))
                .toList();
    }

    @Transactional
    public BonusMinHoursRuleResponse create(BonusMinHoursRuleRequest request) {
        BonusMinHoursRule entity = new BonusMinHoursRule();
        applyRequest(entity, request);

        if (repository.existsByPeriodAndArchivedAtIsNull(entity.getPeriod())) {
            throw new IllegalArgumentException("Active rule already exists for period: " + entity.getPeriod());
        }

        return new BonusMinHoursRuleResponse(repository.save(entity));
    }

    @Transactional
    public BonusMinHoursRuleResponse update(Long id, BonusMinHoursRuleRequest request) {
        BonusMinHoursRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("BonusMinHoursRule not found: " + id));

        applyRequest(entity, request);

        if (repository.existsByPeriodAndArchivedAtIsNullAndIdNot(entity.getPeriod(), id)) {
            throw new IllegalArgumentException("Active rule already exists for period: " + entity.getPeriod());
        }

        return new BonusMinHoursRuleResponse(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        BonusMinHoursRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("BonusMinHoursRule not found: " + id));
        entity.setArchivedAt(OffsetDateTime.now());
        repository.save(entity);
    }

    private void applyRequest(BonusMinHoursRule entity, BonusMinHoursRuleRequest request) {
        entity.setPeriod(YearMonth.from(request.getPeriod()).atDay(1));
        entity.setMinNumHours(request.getMinNumHours());
    }
}

