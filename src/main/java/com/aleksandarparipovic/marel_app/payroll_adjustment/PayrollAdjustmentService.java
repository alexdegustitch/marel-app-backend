package com.aleksandarparipovic.marel_app.payroll_adjustment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollAdjustmentService {

    private final PayrollAdjustmentRepository payrollAdjustmentRepository;

    @Transactional(readOnly = true)
    public List<PayrollAdjustment> findAll() {
        return payrollAdjustmentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public PayrollAdjustment findById(Long id) {
        return payrollAdjustmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustment not found"));
    }

    @Transactional
    public PayrollAdjustment create(PayrollAdjustment entity) {
        entity.setId(null);
        return payrollAdjustmentRepository.save(entity);
    }

    @Transactional
    public PayrollAdjustment update(Long id, PayrollAdjustment entity) {
        if (!payrollAdjustmentRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustment not found");
        }
        entity.setId(id);
        return payrollAdjustmentRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollAdjustmentRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustment not found");
        }
        payrollAdjustmentRepository.deleteById(id);
    }
}
