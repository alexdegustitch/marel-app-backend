package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollRunService {

    private static final String STATUS_DRAFT = "DRAFT";

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final CurrentUserService currentUserService;

    // ── new query endpoints ──────────────────────────────────────────────

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
        return payrollRunItemRepository.findPagedByYearAndMonth(year, month, search, statusFilter, pageable);
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
}
