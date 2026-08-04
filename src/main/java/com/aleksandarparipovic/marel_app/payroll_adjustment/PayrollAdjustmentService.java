package com.aleksandarparipovic.marel_app.payroll_adjustment;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentResponse;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentUpdateRequest;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScope;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollAdjustmentService {

    private final PayrollAdjustmentRepository payrollAdjustmentRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollSchemeScopeService payrollSchemeScopeService;
    private final EntityReferenceProvider referenceProvider;

    @Transactional(readOnly = true)
    public List<PayrollAdjustmentResponse> findAll() {
        return payrollAdjustmentRepository.findAllWithCategory()
                .stream().map(PayrollAdjustmentResponse::new).toList();
    }

    @Transactional(readOnly = true)
    public PayrollAdjustmentResponse findById(Long id) {
        PayrollAdjustment a = payrollAdjustmentRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustment not found"));
        return new PayrollAdjustmentResponse(a);
    }

    @Transactional
    public PayrollAdjustmentResponse create(PayrollAdjustmentCreateRequest request) {
        // Payroll initialisation already skips the lines a scheme excludes, but
        // this endpoint can add one directly. Checked here too, or the exclusion
        // would be a UI convention rather than a rule.
        requireAdjustmentAllowed(request.getPayrollRunItemId(), request.getPayrollAdjustmentCategoryId());
        requireNoExistingRow(request.getPayrollRunItemId(), request.getPayrollAdjustmentCategoryId());

        PayrollAdjustment entity = new PayrollAdjustment();
        entity.setPayrollRunItem(referenceProvider.getRequiredReference(PayrollRunItem.class, request.getPayrollRunItemId(), "payrollRunItemId"));
        entity.setPayrollAdjustmentCategory(referenceProvider.getRequiredReference(PayrollAdjustmentCategory.class, request.getPayrollAdjustmentCategoryId(), "payrollAdjustmentCategoryId"));
        entity.setSystemQuantity(request.getSystemQuantity());
        entity.setQuantity(request.getQuantity());
        entity.setSystemUnitAmount(request.getSystemUnitAmount());
        entity.setUnitAmount(request.getUnitAmount());
        entity.setSystemAmount(request.getSystemAmount() != null ? request.getSystemAmount() : BigDecimal.ZERO);
        entity.setAmount(request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO);
        entity.setIsOverridden(request.getIsOverridden() != null ? request.getIsOverridden() : false);
        entity.setNote(request.getNote());
        entity.setIsApplied(request.getIsApplied() != null ? request.getIsApplied() : true);
        entity.setCreatedAt(OffsetDateTime.now());
        PayrollAdjustment saved = payrollAdjustmentRepository.save(entity);
        return new PayrollAdjustmentResponse(payrollAdjustmentRepository.findByIdWithCategory(saved.getId()).orElseThrow());
    }

    @Transactional
    public PayrollAdjustmentResponse update(Long id, PayrollAdjustmentUpdateRequest request) {
        PayrollAdjustment entity = payrollAdjustmentRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustment not found"));
        if (request.getSystemQuantity() != null)   entity.setSystemQuantity(request.getSystemQuantity());
        if (request.getQuantity() != null)          entity.setQuantity(request.getQuantity());
        if (request.getSystemUnitAmount() != null)  entity.setSystemUnitAmount(request.getSystemUnitAmount());
        if (request.getUnitAmount() != null)        entity.setUnitAmount(request.getUnitAmount());
        if (request.getSystemAmount() != null)      entity.setSystemAmount(request.getSystemAmount());
        if (request.getAmount() != null)            entity.setAmount(request.getAmount());
        if (request.getIsOverridden() != null)      entity.setIsOverridden(request.getIsOverridden());
        if (request.getNote() != null)              entity.setNote(request.getNote());
        if (request.getIsApplied() != null)         entity.setIsApplied(request.getIsApplied());
        entity.setUpdatedAt(OffsetDateTime.now());
        payrollAdjustmentRepository.save(entity);
        return new PayrollAdjustmentResponse(payrollAdjustmentRepository.findByIdWithCategory(id).orElseThrow());
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollAdjustmentRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustment not found");
        }
        payrollAdjustmentRepository.deleteById(id);
    }

    /**
     * Refuse a second row of the same category on one payroll item (D9).
     *
     * <p>{@code uq_payroll_adjustment_item_category} is the real guarantee — two
     * concurrent requests can each pass this check and then both insert. This
     * exists so the ordinary case fails with a sentence somebody can act on rather
     * than a constraint violation.
     *
     * <p>A category is a labelled slot on the payslip, not a ledger: there is one
     * "Ostalo" line and it holds one number. Several entries that need to stay
     * apart belong in separate categories, which is configuration.
     */
    private void requireNoExistingRow(Long payrollRunItemId, Long adjustmentCategoryId) {
        if (payrollRunItemId == null || adjustmentCategoryId == null) {
            return;
        }
        boolean exists = payrollAdjustmentRepository
                .findByPayrollRunItemIdWithCategory(payrollRunItemId).stream()
                .anyMatch(a -> a.getPayrollAdjustmentCategory().getId().equals(adjustmentCategoryId));

        if (exists) {
            throw new ConflictException(
                    "Ova stavka već postoji na ovom obračunu. Izmenite postojeću umesto dodavanja nove.");
        }
    }

    /**
     * Refuse an adjustment line the employee's compensation scheme excludes.
     *
     * <p>Resolved over the item's own payroll period, and permissive when
     * anything needed is missing — this is a guard against a wrong line, not an
     * extra way for payroll editing to fail.
     */
    private void requireAdjustmentAllowed(Long payrollRunItemId, Long adjustmentCategoryId) {
        if (payrollRunItemId == null || adjustmentCategoryId == null) {
            return;
        }
        payrollRunItemRepository.findById(payrollRunItemId).ifPresent(item -> {
            if (item.getEmployee() == null || item.getPeriod() == null) {
                return;
            }
            LocalDate periodStart = item.getPeriod().withDayOfMonth(1);
            LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());

            PayrollSchemeScope scope = payrollSchemeScopeService.scopeFor(
                    item.getEmployee().getId(), periodStart, periodEnd);

            if (scope != null && !scope.allowsAdjustmentCategory(adjustmentCategoryId)) {
                throw new ConflictException(
                        "Ova stavka obračuna nije predviđena za način obračuna ovog zaposlenog.");
            }
        });
    }
}
