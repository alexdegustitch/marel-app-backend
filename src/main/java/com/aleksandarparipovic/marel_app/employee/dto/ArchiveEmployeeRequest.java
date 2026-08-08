package com.aleksandarparipovic.marel_app.employee.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class ArchiveEmployeeRequest {

    @NotBlank
    private String password;

    /**
     * Last day of employment — the date the person actually stopped working.
     *
     * <p>Optional; today applies when absent, which is what every caller before
     * this field got. The common case is a date in the PAST: somebody left on
     * the 15th and the paperwork is done on the 20th, and payroll for that month
     * has to see the 15th.
     *
     * <p>Deliberately NOT the same thing as {@code employees.archived_at}. This
     * date closes the employment PERIOD; archived_at records when the row was
     * hidden, which is an audit fact about the action and stays {@code now()}.
     * Conflating "when did they stop working" with "when did somebody click
     * archive" is how the first of those two silently becomes unanswerable.
     */
    private LocalDate employmentEndDate;
}
