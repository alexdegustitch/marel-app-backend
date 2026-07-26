package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Shared filter contract for all 5 analytics endpoints. Each query method reads only the
// fields relevant to its page (documented per-method in AnalyticsQueryRepository); unused
// fields are simply ignored.
@Data
public class AnalyticsFilterRequest {

    private List<LocalDate> dates;
    private LocalDate dateFrom;
    private LocalDate dateTo;

    private List<LocalDate> months;          // first-of-month markers, matched against month_start

    private List<Long> shiftIds;
    private List<Long> productionOrderIds;

    private List<String> notes;              // exact-match multi-select
    private String noteLike;                 // ILIKE '%...%' fragment

    private List<LocalTime> startTimes;
    private LocalTime startTimeFrom;
    private LocalTime startTimeTo;

    private List<Long> productIds;
    private List<Long> operationIds;
    private List<Long> employeeIds;          // page 2/3 only

    // page 2 sidebar only — filters on raw work_log_facts.duration_min (per-log, pre-aggregation)
    private Integer durationMinFrom;
    private Integer durationMinTo;
}
