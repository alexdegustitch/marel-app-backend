package com.aleksandarparipovic.marel_app.bonus_eligibility_rules;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleBulkUpdateRequest;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRulePatchItem;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRuleResponse;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRulesByMonthDto;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto.BonusEligibilityRulesByYearDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BonusEligibilityRuleService {

    private final BonusEligibilityRuleRepository repository;
    private final PayrollRunItemRepository payrollRunItemRepository;

    @Transactional
    public List<BonusEligibilityRuleResponse> bulkUpdate(BonusEligibilityRuleBulkUpdateRequest request) {
        List<BonusEligibilityRuleResponse> updated = request.getRules().stream()
                .map(patch -> {
                    BonusEligibilityRule entity = repository.findByIdAndArchivedAtIsNull(patch.getId())
                            .orElseThrow(() -> new IllegalArgumentException("BonusEligibilityRule not found: " + patch.getId()));
                    if (patch.getMinNumHours() != null)  entity.setMinNumHours(patch.getMinNumHours());
                    if (patch.getSaturdayCount() != null) entity.setSaturdayCount(patch.getSaturdayCount());
                    if (patch.getBonusValue() != null)   entity.setBonusValue(patch.getBonusValue());
                    if (patch.getNote() != null)         entity.setNote(patch.getNote());
                    return new BonusEligibilityRuleResponse(repository.save(entity));
                })
                .toList();

        payrollRunItemRepository.markNeedsRecalculationByYearAndMonth(request.getYear(), request.getMonth());

        return updated;
    }

    @Transactional(readOnly = true)
    public List<BonusEligibilityRuleResponse> findAllActive() {
        return repository.findByArchivedAtIsNullOrderByPeriodDesc().stream()
                .map(BonusEligibilityRuleResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BonusEligibilityRuleResponse> findByPeriod(LocalDate period) {
        LocalDate normalized = YearMonth.from(period).atDay(1);
        return repository.findByPeriodAndArchivedAtIsNullOrderByMinNumHoursAsc(normalized).stream()
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

        BonusEligibilityRuleResponse response = new BonusEligibilityRuleResponse(repository.save(entity));

        YearMonth ym = YearMonth.from(entity.getPeriod());
        payrollRunItemRepository.markNeedsRecalculationByYearAndMonth(ym.getYear(), ym.getMonthValue());

        return response;
    }

    @Transactional
    public void delete(Long id) {
        BonusEligibilityRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("Bonus eligibility rule not found: " + id));
        entity.setArchivedAt(OffsetDateTime.now());
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<BonusEligibilityRulesByYearDto> findGroupedByYear() {
        List<BonusEligibilityRule> all = repository.findByArchivedAtIsNullOrderByPeriodDesc();

        // group by year → month → rules sorted by saturdayCount asc
        Map<Integer, Map<Integer, List<BonusEligibilityRuleResponse>>> byYearMonth = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getPeriod().getYear(),
                        Collectors.groupingBy(
                                r -> r.getPeriod().getMonthValue(),
                                Collectors.mapping(BonusEligibilityRuleResponse::new, Collectors.toList())
                        )
                ));

        return byYearMonth.entrySet().stream()
                .sorted(Map.Entry.<Integer, Map<Integer, List<BonusEligibilityRuleResponse>>>comparingByKey().reversed())
                .map(yearEntry -> {
                    List<BonusEligibilityRulesByMonthDto> months = yearEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<Integer, List<BonusEligibilityRuleResponse>>comparingByKey().reversed())
                            .map(monthEntry -> {
                                List<BonusEligibilityRuleResponse> rules = monthEntry.getValue().stream()
                                        .sorted(Comparator.comparingInt(r -> r.getSaturdayCount() != null ? r.getSaturdayCount() : 0))
                                        .toList();
                                return new BonusEligibilityRulesByMonthDto(monthEntry.getKey(), rules);
                            })
                            .toList();
                    return new BonusEligibilityRulesByYearDto(yearEntry.getKey(), months);
                })
                .toList();
    }

    @Transactional
    public List<BonusEligibilityRuleResponse> initializeForMonth(int year, int month) {
        LocalDate period = YearMonth.of(year, month).atDay(1);

        if (repository.existsByPeriodAndArchivedAtIsNull(period)) {
            return repository.findByPeriodAndArchivedAtIsNullOrderByMinNumHoursAsc(period)
                    .stream()
                    .map(BonusEligibilityRuleResponse::new)
                    .toList();
        }

        int saturdayCount = countSaturdaysInMonth(year, month);

        List<BonusEligibilityRule> rules = new ArrayList<>();
        for (int i = 1; i <= saturdayCount; i++) {
            BonusEligibilityRule rule = new BonusEligibilityRule();
            rule.setPeriod(period);
            rule.setSaturdayCount(i);
            rule.setMinNumHours(0);
            rule.setBonusValue(BigDecimal.ZERO);
            rules.add(rule);
        }

        return repository.saveAll(rules).stream()
                .map(BonusEligibilityRuleResponse::new)
                .toList();
    }

    private int countSaturdaysInMonth(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        int count = 0;
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            if (LocalDate.of(year, month, day).getDayOfWeek() == DayOfWeek.SATURDAY) {
                count++;
            }
        }
        return count;
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
