package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollAdjustmentCategoryService {

    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;

    @Transactional(readOnly = true)
    public List<PayrollAdjustmentCategory> findAll() {
        return payrollAdjustmentCategoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public PayrollAdjustmentCategory findById(Long id) {
        return payrollAdjustmentCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustmentCategory not found"));
    }

    @Transactional
    public PayrollAdjustmentCategory create(PayrollAdjustmentCategory entity) {
        entity.setId(null);
        return payrollAdjustmentCategoryRepository.save(entity);
    }

    @Transactional
    public PayrollAdjustmentCategory update(Long id, PayrollAdjustmentCategory entity) {
        if (!payrollAdjustmentCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustmentCategory not found");
        }
        entity.setId(id);
        return payrollAdjustmentCategoryRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollAdjustmentCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustmentCategory not found");
        }
        payrollAdjustmentCategoryRepository.deleteById(id);
    }
}
