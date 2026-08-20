package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleRequest;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleResponse;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRulesByYearDto;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleHistoryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
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
    private final BonusMinHoursRuleHistoryRepository historyRepository;
    private final BonusMinHoursHistoryRecorder historyRecorder;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final CurrentUserService currentUserService;

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

    /**
     * Sets a month's minimum by hand.
     *
     * <p>The calendar's own answer is left where it is and goes on being maintained: this
     * only decides which of the two applies. Resetting later therefore returns the month to
     * whatever the calendar says THEN, not to what it said when the override was made.
     */
    @Transactional
    public BonusMinHoursRuleResponse setManual(Long id, Integer manualMinNumHours, String note) {
        if (manualMinNumHours == null || manualMinNumHours <= 0) {
            throw new IllegalArgumentException("Ručno postavljen minimum mora biti veći od nule.");
        }

        BonusMinHoursRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("BonusMinHoursRule not found: " + id));

        requireMonthNotLocked(entity.getPeriod());

        entity.setManualMinNumHours(manualMinNumHours);
        entity.setManualSetAt(OffsetDateTime.now());
        entity.setManualSetBy(currentUserService.getCurrentUserId());
        BonusMinHoursRule saved = repository.saveAndFlush(entity);

        historyRecorder.record(saved.getPeriod(), saved.getMinNumHours(), manualMinNumHours,
                BonusMinHoursRuleHistory.Source.MANUAL_SET, currentUserService.getCurrentUserId(), note);

        markMonthForRecalculation(saved.getPeriod());

        return new BonusMinHoursRuleResponse(saved);
    }

    /**
     * Drops the manual value, handing the month back to the work calendar.
     *
     * <p>The value it lands on is the calendar's CURRENT answer, which is the point of keeping
     * the two apart: a month overridden in March and reset in July returns to July's calendar,
     * not to March's.
     */
    @Transactional
    public BonusMinHoursRuleResponse resetManual(Long id, String note) {
        BonusMinHoursRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("BonusMinHoursRule not found: " + id));

        requireMonthNotLocked(entity.getPeriod());

        if (entity.getManualMinNumHours() == null) {
            return new BonusMinHoursRuleResponse(entity);
        }

        entity.setManualMinNumHours(null);
        entity.setManualSetAt(null);
        entity.setManualSetBy(null);
        BonusMinHoursRule saved = repository.saveAndFlush(entity);

        historyRecorder.record(saved.getPeriod(), saved.getMinNumHours(), null,
                BonusMinHoursRuleHistory.Source.MANUAL_RESET, currentUserService.getCurrentUserId(), note);

        markMonthForRecalculation(saved.getPeriod());

        return new BonusMinHoursRuleResponse(saved);
    }

    /** A month's history, newest first. */
    @Transactional(readOnly = true)
    public List<BonusMinHoursRuleHistoryDto> findHistory(Long id) {
        BonusMinHoursRule entity = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("BonusMinHoursRule not found: " + id));

        return historyRepository.findByPeriodOrderByValidFromDesc(entity.getPeriod()).stream()
                .map(BonusMinHoursRuleHistoryDto::new)
                .toList();
    }

    /**
     * A locked month is not calculated from anything any more.
     *
     * <p>Refused rather than allowed-and-ignored: letting the rule change while the figures
     * stay put would leave the screen saying one thing and the payslip another.
     */
    private void requireMonthNotLocked(java.time.LocalDate period) {
        long locked = payrollRunItemRepository.countLockedForMonth(period.getYear(), period.getMonthValue());
        if (locked > 0) {
            throw new IllegalArgumentException(
                    "Mesec je zaključan u obračunu i minimalni sati se ne mogu menjati.");
        }
    }

    private void markMonthForRecalculation(java.time.LocalDate period) {
        payrollRunItemRepository.markNeedsRecalculationByYearAndMonth(period.getYear(), period.getMonthValue());
    }

    private void applyRequest(BonusMinHoursRule entity, BonusMinHoursRuleRequest request) {
        entity.setPeriod(YearMonth.from(request.getPeriod()).atDay(1));
        entity.setMinNumHours(request.getMinNumHours());
    }
}

