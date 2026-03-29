package com.aleksandarparipovic.marel_app.monthly_report;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private final MonthlyReportRepository monthlyReportRepository;

    @Transactional(readOnly = true)
    public List<MonthlyReport> findAll() {
        return monthlyReportRepository.findAll();
    }

    @Transactional(readOnly = true)
    public MonthlyReport findById(Long id) {
        return monthlyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MonthlyReport not found"));
    }

    @Transactional
    public MonthlyReport create(MonthlyReport entity) {
        entity.setId(null);
        return monthlyReportRepository.save(entity);
    }

    @Transactional
    public MonthlyReport update(Long id, MonthlyReport entity) {
        if (!monthlyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("MonthlyReport not found");
        }
        entity.setId(id);
        return monthlyReportRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!monthlyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("MonthlyReport not found");
        }
        monthlyReportRepository.deleteById(id);
    }
}
