package com.aleksandarparipovic.marel_app.payroll_adjustment;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentResponse;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentUpdateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollAdjustmentService {

    private final PayrollAdjustmentRepository payrollAdjustmentRepository;
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
}
