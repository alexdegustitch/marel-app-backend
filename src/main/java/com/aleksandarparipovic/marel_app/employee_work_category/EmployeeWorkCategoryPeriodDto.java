package com.aleksandarparipovic.marel_app.employee_work_category;

import java.time.LocalDate;

public record EmployeeWorkCategoryPeriodDto(
        Long id,
        Long workCodeCategoryId,
        String categoryNo,
        String categoryName,
        LocalDate validFrom,
        LocalDate validTo,
        String note
) {
    public static EmployeeWorkCategoryPeriodDto from(EmployeeWorkCategoryPeriod p) {
        return new EmployeeWorkCategoryPeriodDto(
                p.getId(),
                p.getWorkCodeCategory().getId(),
                p.getWorkCodeCategory().getCategoryNo(),
                p.getWorkCodeCategory().getCategoryName(),
                p.getValidFrom(),
                p.getValidTo(),
                p.getNote());
    }
}
