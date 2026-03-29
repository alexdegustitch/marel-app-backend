package com.aleksandarparipovic.marel_app.payroll_run_item;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollRunItemService {

    private final PayrollRunItemRepository payrollRunItemRepository;

    @Transactional(readOnly = true)
    public List<PayrollRunItem> findAll() {
        return payrollRunItemRepository.findAll();
    }

    @Transactional(readOnly = true)
    public PayrollRunItem findById(Long id) {
        return payrollRunItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found"));
    }

    @Transactional
    public PayrollRunItem create(PayrollRunItem entity) {
        entity.setId(null);
        return payrollRunItemRepository.save(entity);
    }

    @Transactional
    public PayrollRunItem update(Long id, PayrollRunItem entity) {
        if (!payrollRunItemRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRunItem not found");
        }
        entity.setId(id);
        return payrollRunItemRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollRunItemRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRunItem not found");
        }
        payrollRunItemRepository.deleteById(id);
    }
}
