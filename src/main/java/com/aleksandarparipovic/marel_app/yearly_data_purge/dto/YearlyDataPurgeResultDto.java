package com.aleksandarparipovic.marel_app.yearly_data_purge.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class YearlyDataPurgeResultDto {

    private final Integer year;
    private final boolean executed;
    private final long employeePayrollRunItemUpdates;
    private final long employeeRecordUpdates;
    private final long employeeRecords;
    private final long payrollAdjustments;
    private final long payrollRunItemCategories;
    private final long payrollRunItems;
    private final long payrollRuns;
    private final long monthlyReportCategories;
    private final long monthlyReportRecalcQueue;
    private final long monthlyReports;
    private final long dailyReportCategories;
    private final long dailyReportRecalcQueue;
    private final long dailyReports;
    private final long workLogs;
    private final long workShifts;
}


