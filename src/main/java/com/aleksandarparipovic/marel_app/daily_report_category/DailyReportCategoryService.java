package com.aleksandarparipovic.marel_app.daily_report_category;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyReportCategoryService {

    private final DailyReportCategoryRepository dailyReportCategoryRepository;

    @Transactional(readOnly = true)
    public List<DailyReportCategory> findAll() {
        return dailyReportCategoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public DailyReportCategory findById(Long id) {
        return dailyReportCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DailyReportCategory not found"));
    }

    @Transactional
    public DailyReportCategory create(DailyReportCategory entity) {
        entity.setId(null);
        return dailyReportCategoryRepository.save(entity);
    }

    @Transactional
    public DailyReportCategory update(Long id, DailyReportCategory entity) {
        if (!dailyReportCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("DailyReportCategory not found");
        }
        entity.setId(id);
        return dailyReportCategoryRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!dailyReportCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("DailyReportCategory not found");
        }
        dailyReportCategoryRepository.deleteById(id);
    }
}
