package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollRunItemCategoryService {

    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;

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
    public PayrollRunItemCategory create(PayrollRunItemCategory entity) {
        entity.setId(null);
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
}
