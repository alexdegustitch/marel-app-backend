package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.LikePattern;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollMonthAggregate;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRecentDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoMasked;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSearchHit;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollYearOverview;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PayrollRunService {

    private static final String STATUS_DRAFT = "DRAFT";

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final CurrentUserService currentUserService;
    private final PayrollVisibilityPolicy payrollVisibilityPolicy;
    private final com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository employeeRepository;

    // ── The handover (SENT) ──────────────────────────────────────────────────

    /** The two columns the sent-payrolls screen may sort by, by their API names. */
    private static final Map<String, String> SENT_SORT_ALIASES = Map.of(
            "employeeNo", "employeeNo",
            "approvedAt", "approvedAt",
            "sentAt", "sentAt");

    /**
     * The handed-over payrolls of one month, sorted the way the director asked:
     * by the worker's number or by when the month was last approved, both ways.
     * Sort keys are whitelisted here, so nothing user-typed reaches an ORDER BY.
     */
    @Transactional(readOnly = true)
    public Page<com.aleksandarparipovic.marel_app.payroll_run_item.dto.SentPayrollRow> getSentByYearAndMonth(
            int year, int month, String sort, String dir, int page, int size) {
        requireSensibleYear(year);
        String alias = SENT_SORT_ALIASES.getOrDefault(sort, "employeeNo");
        org.springframework.data.domain.Sort.Direction direction =
                "desc".equalsIgnoreCase(dir)
                        ? org.springframework.data.domain.Sort.Direction.DESC
                        : org.springframework.data.domain.Sort.Direction.ASC;
        return payrollRunItemRepository.findSentByYearAndMonth(year, month,
                org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), Math.max(1, Math.min(size, 200)),
                        org.springframework.data.domain.Sort.by(direction, alias)));
    }

    /** Months of the year that hold handed-over payrolls, for the month picker. */
    @Transactional(readOnly = true)
    public List<PayrollRunItemRepository.MonthSentCount> getMonthsWithSent(int year) {
        requireSensibleYear(year);
        return payrollRunItemRepository.findMonthsWithSent(year);
    }

    /**
     * Whether the month is ready to be handed over as a whole, and if not, why
     * not — answered by the server so the button and the endpoint cannot drift.
     */
    @Transactional(readOnly = true)
    public PayrollMonthReadiness getReadiness(int year, int month) {
        requireSensibleYear(year);
        long drafts = 0, approved = 0, sent = 0, locked = 0;
        for (PayrollMonthAggregate row : payrollRunItemRepository.aggregateMonthsOfYear(year)) {
            if (row.getMonth() != null && row.getMonth() == month) {
                drafts = zero(row.getDraftCount());
                approved = zero(row.getApprovedCount());
                sent = zero(row.getSentCount());
                locked = zero(row.getLockedCount());
            }
        }
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        List<String> missing = employeeRepository.findEmployedWithoutPayrollForMonth(
                year, month, ym.atDay(1), ym.atEndOfMonth());
        boolean ready = drafts == 0 && approved > 0 && missing.isEmpty();
        return new PayrollMonthReadiness(ready, drafts, approved, sent, locked,
                missing.size(), missing.stream().limit(10).toList());
    }

    private static long zero(Long v) { return v == null ? 0 : v; }

    /**
     * {@code missingEmployees} carries at most ten names — enough to act on;
     * {@code missingEmployeeCount} is the real number.
     */
    public record PayrollMonthReadiness(
            boolean readyToSend,
            long draftCount,
            long approvedCount,
            long sentCount,
            long lockedCount,
            long missingEmployeeCount,
            List<String> missingEmployees) {
    }

    // ── new query endpoints ──────────────────────────────────────────────

    /** Years the Obračuni view may offer: every year that holds at least one live run. */
    @Transactional(readOnly = true)
    public List<Integer> getYearsWithRuns() {
        return payrollRunRepository.findYearsWithRuns();
    }

    @Transactional(readOnly = true)
    public List<PayrollRunSummaryDto> getSummariesByYear(int year) {
        return payrollRunItemRepository.findSummariesByYear(year);
    }

    @Transactional(readOnly = true)
    public List<PayrollRunSummaryDto> getLastActivity(int year, int month) {
        Long userId = currentUserService.getCurrentUserId();
        return payrollRunItemRepository.findLastActivityByUserAndMonth(userId, year, month);
    }

    @Transactional(readOnly = true)
    public Page<PayrollRunInfoDto> getPagedByYearAndMonth(int year, int month, String globalSearch, String status, Pageable pageable) {
        String search = (globalSearch == null || globalSearch.isBlank()) ? null : globalSearch;
        String statusFilter = (status == null || status.isBlank()) ? null : status;
        // The director reads only what was handed over: rows still being
        // prepared are withheld from their list entirely, not just masked.
        boolean onlySent = payrollVisibilityPolicy.seesOnlySentPayrolls();
        Page<PayrollRunInfoDto> page =
                payrollRunItemRepository.findPagedByYearAndMonth(year, month, search, statusFilter, onlySent, pageable);

        // Withheld in the RESPONSE, not by the screen: a figure the browser
        // never receives cannot be read out of the network tab.
        if (payrollVisibilityPolicy.canSeeAmounts()) {
            return page;
        }
        return page.map(row -> PayrollRunInfoMasked.withoutAmounts(
                row, payrollVisibilityPolicy.visibleStatus(row.getStatus())));
    }

    // ── existing ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PayrollRun> findAll() {
        return payrollRunRepository.findAll();
    }

    @Transactional(readOnly = true)
    public PayrollRun findById(Long id) {
        return payrollRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRun not found"));
    }

    @Transactional
    public PayrollRun create(PayrollRunCreateRequest request) {
        PayrollRun entity = new PayrollRun();
        entity.setId(null);
        entity.setReportYear(request.getReportYear());
        entity.setReportMonth(request.getReportMonth());
        initializeCreateDefaults(entity);
        return payrollRunRepository.save(entity);
    }

    @Transactional
    public PayrollRun update(Long id, PayrollRun entity) {
        if (!payrollRunRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRun not found");
        }
        entity.setId(id);
        return payrollRunRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollRunRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRun not found");
        }
        payrollRunRepository.deleteById(id);
    }

    private void initializeCreateDefaults(PayrollRun run) {
        if (run.getStatus() == null) run.setStatus(STATUS_DRAFT);
        if (run.getRunCode() == null || run.getRunCode().isBlank()) {
            run.setRunCode("RUN-" + run.getReportYear() + "-" + String.format("%02d", run.getReportMonth()));
        }
        if (run.getCreatedAt() == null) run.setCreatedAt(OffsetDateTime.now());
    }


    // ── The year view ────────────────────────────────────────────────────────

    /** How many of the caller's recently opened obračuni each month lists. */
    private static final int RECENT_PER_MONTH = 3;

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    /**
     * A whole year of obračuni in one answer — see {@link PayrollYearOverview}.
     *
     * <p>Masked in the RESPONSE the way the paged list is: somebody without
     * payroll access gets no sums and no locked count, and every locked month
     * counted as approved.
     */
    @Transactional(readOnly = true)
    public PayrollYearOverview getYearOverview(int year) {
        requireSensibleYear(year);
        boolean amountsVisible = payrollVisibilityPolicy.canSeeAmounts();

        Map<Integer, PayrollMonthAggregate> totals = new HashMap<>();
        for (PayrollMonthAggregate row : payrollRunItemRepository.aggregateMonthsOfYear(year)) {
            totals.put(row.getMonth(), row);
        }

        Map<Integer, List<PayrollYearOverview.RecentPayroll>> recent = new HashMap<>();
        Long userId = currentUserService.getCurrentUserId();
        if (userId != null) {
            for (PayrollRecentDto row : payrollRunItemRepository.findRecentPerMonthForUser(userId, year, RECENT_PER_MONTH)) {
                recent.computeIfAbsent(row.getMonth(), m -> new ArrayList<>())
                        .add(new PayrollYearOverview.RecentPayroll(
                                row.getMonthlyReportId(), row.getEmployeeId(), row.getEmployeeName(), row.getUpdateTime()));
            }
        }

        List<PayrollYearOverview.MonthOverview> months = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            PayrollMonthAggregate t = totals.get(month);
            List<PayrollYearOverview.RecentPayroll> r = List.copyOf(recent.getOrDefault(month, List.of()));
            PayrollYearOverview.MonthOverview full = t == null
                    ? new PayrollYearOverview.MonthOverview(month, 0, 0, 0, 0, 0L, null, null, null, r)
                    : new PayrollYearOverview.MonthOverview(
                            month,
                            t.getItemCount(),
                            t.getDraftCount(),
                            t.getApprovedCount(),
                            t.getSentCount() == null ? 0 : t.getSentCount(),
                            t.getLockedCount(),
                            t.getTotalNetPayable(),
                            t.getTotalNetEarnings(),
                            t.getLastActivityAt(),
                            r);
            months.add(amountsVisible ? full : full.withoutAmounts());
        }
        return new PayrollYearOverview(year, amountsVisible, months);
    }

    /**
     * The obračuni of one year found by a fragment of a worker's name or number,
     * each with the status the caller is allowed to know.
     */
    @Transactional(readOnly = true)
    public List<PayrollRunSearchHit> searchInYear(int year, String query, int size) {
        requireSensibleYear(year);
        String fragment = query == null ? "" : query.strip();
        if (fragment.isEmpty()) {
            return List.of();
        }
        boolean onlySent = payrollVisibilityPolicy.seesOnlySentPayrolls();
        return payrollRunItemRepository
                .searchInYear(year, LikePattern.contains(fragment), Math.max(1, Math.min(size, 25)))
                .stream()
                // The director's search finds only handed-over payrolls, the same
                // rule as their list — a hit they cannot open is a broken door.
                .filter(row -> !onlySent
                        || "SENT".equals(row.getStatus()) || "LOCKED".equals(row.getStatus()))
                .map(row -> PayrollRunSearchHit.of(row, payrollVisibilityPolicy.visibleStatus(row.getStatus())))
                .toList();
    }

    private static void requireSensibleYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("year must be between " + MIN_YEAR + " and " + MAX_YEAR + ": " + year);
        }
    }
}
