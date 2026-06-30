package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollAdjustmentCategoryService {

    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;

    @Transactional(readOnly = true)
    public List<PayrollAdjustmentCategoryResponse> findAll() {
        return payrollAdjustmentCategoryRepository.findAll()
                .stream().map(PayrollAdjustmentCategoryResponse::new).toList();
    }

    @Transactional(readOnly = true)
    public PayrollAdjustmentCategoryResponse findById(Long id) {
        return new PayrollAdjustmentCategoryResponse(payrollAdjustmentCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustmentCategory not found")));
    }

    @Transactional
    public PayrollAdjustmentCategoryResponse create(PayrollAdjustmentCategoryCreateRequest request) {
        PayrollAdjustmentCategory entity = new PayrollAdjustmentCategory();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setSectionCode(request.getSectionCode());
        entity.setSectionOrder(request.getSectionOrder());
        entity.setSortOrder(request.getSortOrder());
        entity.setImpactCode(request.getImpactCode());
        entity.setInputType(request.getInputType());
        entity.setIsManual(request.getIsManual() != null ? request.getIsManual() : false);
        entity.setAllowOverride(request.getAllowOverride() != null ? request.getAllowOverride() : false);
        entity.setOverrideTarget(request.getOverrideTarget());
        entity.setAllowNegative(request.getAllowNegative() != null ? request.getAllowNegative() : false);
        entity.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        entity.setVisibleInUi(request.getVisibleInUi() != null ? request.getVisibleInUi() : true);
        entity.setVisibleInPdf(request.getVisibleInPdf() != null ? request.getVisibleInPdf() : true);
        entity.setCalculationKey(request.getCalculationKey());
        entity.setCreatedAt(OffsetDateTime.now());
        return new PayrollAdjustmentCategoryResponse(payrollAdjustmentCategoryRepository.save(entity));
    }

    @Transactional
    public PayrollAdjustmentCategoryResponse update(Long id, PayrollAdjustmentCategoryCreateRequest request) {
        PayrollAdjustmentCategory entity = payrollAdjustmentCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustmentCategory not found"));
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setSectionCode(request.getSectionCode());
        entity.setSectionOrder(request.getSectionOrder());
        entity.setSortOrder(request.getSortOrder());
        entity.setImpactCode(request.getImpactCode());
        entity.setInputType(request.getInputType());
        if (request.getIsManual() != null)      entity.setIsManual(request.getIsManual());
        if (request.getAllowOverride() != null)  entity.setAllowOverride(request.getAllowOverride());
        entity.setOverrideTarget(request.getOverrideTarget());
        if (request.getAllowNegative() != null)  entity.setAllowNegative(request.getAllowNegative());
        if (request.getIsActive() != null)       entity.setIsActive(request.getIsActive());
        if (request.getVisibleInUi() != null)    entity.setVisibleInUi(request.getVisibleInUi());
        if (request.getVisibleInPdf() != null)   entity.setVisibleInPdf(request.getVisibleInPdf());
        entity.setCalculationKey(request.getCalculationKey());
        entity.setUpdatedAt(OffsetDateTime.now());
        return new PayrollAdjustmentCategoryResponse(payrollAdjustmentCategoryRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollAdjustmentCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustmentCategory not found");
        }
        payrollAdjustmentCategoryRepository.deleteById(id);
    }
}
