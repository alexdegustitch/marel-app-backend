package com.aleksandarparipovic.marel_app.daily_report;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyReportService {

    private final DailyReportRepository dailyReportRepository;

    @Transactional(readOnly = true)
    public List<DailyReport> findAll() {
        return dailyReportRepository.findAll();
    }

    @Transactional(readOnly = true)
    public DailyReport findById(Long id) {
        return dailyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DailyReport not found"));
    }

    @Transactional
    public DailyReport create(DailyReport entity) {
        entity.setId(null);
        return dailyReportRepository.save(entity);
    }

    @Transactional
    public DailyReport update(Long id, DailyReport entity) {
        if (!dailyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("DailyReport not found");
        }
        entity.setId(id);
        return dailyReportRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!dailyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("DailyReport not found");
        }
        dailyReportRepository.deleteById(id);
    }
}
