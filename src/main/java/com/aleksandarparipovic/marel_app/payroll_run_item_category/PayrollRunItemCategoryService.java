package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.dto.PayrollRunItemCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollRunItemCategoryService {

    private static final String SOURCE_TYPE_WORK = "WORK";

    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    private final EntityReferenceProvider referenceProvider;

    @Transactional(readOnly = true)
    public List<PayrollRunItemCategory> findAll() {
        return payrollRunItemCategoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public PayrollRunItemCategory findById(Long id) {
        return payrollRunItemCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItemCategory not found"));
    }

    @Transactional
    public PayrollRunItemCategory create(PayrollRunItemCategoryCreateRequest request) {
        PayrollRunItemCategory entity = new PayrollRunItemCategory();
        entity.setId(null);
        entity.setPayrollRunItem(referenceProvider.getRequiredReference(PayrollRunItem.class, request.getPayrollRunItemId(), "payrollRunItemId"));
        entity.setWorkCodeCategory(referenceProvider.getRequiredReference(WorkCodeCategory.class, request.getWorkCodeCategoryId(), "workCodeCategoryId"));
        initializeCreateDefaults(entity);
        return payrollRunItemCategoryRepository.save(entity);
    }

    @Transactional
    public PayrollRunItemCategory update(Long id, PayrollRunItemCategory entity) {
        if (!payrollRunItemCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRunItemCategory not found");
        }
        entity.setId(id);
        return payrollRunItemCategoryRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollRunItemCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRunItemCategory not found");
        }
        payrollRunItemCategoryRepository.deleteById(id);
    }

    private void initializeCreateDefaults(PayrollRunItemCategory category) {
        if (category.getSourceType() == null || category.getSourceType().isBlank()) {
            category.setSourceType(SOURCE_TYPE_WORK);
        }

        if (category.getTotalMinutes() == null) category.setTotalMinutes(0);
        if (category.getTotalPaidMinutes() == null) category.setTotalPaidMinutes(0);
        if (category.getTotalQuantity() == null) category.setTotalQuantity(0);
        if (category.getTotalScrap() == null) category.setTotalScrap(0);

        if (category.getWeightedNormMinutes() == null) category.setWeightedNormMinutes(BigDecimal.ZERO);
        if (category.getCategoryCoefficientSnapshot() == null) category.setCategoryCoefficientSnapshot(BigDecimal.ZERO);
        if (category.getEffectiveMinutes() == null) category.setEffectiveMinutes(BigDecimal.ZERO);
        if (category.getHourlyRate() == null) category.setHourlyRate(BigDecimal.ZERO);
        if (category.getAmount() == null) category.setAmount(BigDecimal.ZERO);

        if (category.getCreatedAt() == null) category.setCreatedAt(OffsetDateTime.now());
    }
}
