package com.aleksandarparipovic.marel_app.employment_period;

import java.time.LocalDate;

/** One spell of employment, as the employee screen shows it. */
public record EmploymentPeriodDto(
        Long id,
        LocalDate startedOn,
        /** Null = still employed. Set by ARCHIVING the employee, never typed. */
        LocalDate endedOn,
        Integer normGraceDays,
        LocalDate probationEndDate,
        String note
) {
    public static EmploymentPeriodDto from(EmployeeEmploymentPeriod p) {
        return new EmploymentPeriodDto(
                p.getId(), p.getStartedOn(), p.getEndedOn(),
                p.getNormGraceDays(), p.getProbationEndDate(), p.getNote());
    }
}
