package com.aleksandarparipovic.marel_app.monthly_report.dto;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class MonthlyReportCreateRequest {
    @NotNull
    private Long employeeId;
    @NotNull
    private Long employeeRecordId;
    @NotNull
    private Integer year;
    @NotNull
    private Integer month;
}
