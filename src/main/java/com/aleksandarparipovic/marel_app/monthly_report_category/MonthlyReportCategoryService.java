package com.aleksandarparipovic.marel_app.monthly_report_category;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MonthlyReportCategoryService {

    private final MonthlyReportCategoryRepository monthlyReportCategoryRepository;

    @Transactional(readOnly = true)
    public List<MonthlyReportCategory> findAll() {
        return monthlyReportCategoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public MonthlyReportCategory findById(Long id) {
        return monthlyReportCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MonthlyReportCategory not found"));
    }

    @Transactional
    public MonthlyReportCategory create(MonthlyReportCategory entity) {
        entity.setId(null);
        return monthlyReportCategoryRepository.save(entity);
    }

    @Transactional
    public MonthlyReportCategory update(Long id, MonthlyReportCategory entity) {
        if (!monthlyReportCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("MonthlyReportCategory not found");
        }
        entity.setId(id);
        return monthlyReportCategoryRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!monthlyReportCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("MonthlyReportCategory not found");
        }
        monthlyReportCategoryRepository.deleteById(id);
    }
}
