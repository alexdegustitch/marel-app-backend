package com.aleksandarparipovic.marel_app.payroll_run;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayrollRunService {

    private final PayrollRunRepository payrollRunRepository;

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
    public PayrollRun create(PayrollRun entity) {
        entity.setId(null);
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
}
