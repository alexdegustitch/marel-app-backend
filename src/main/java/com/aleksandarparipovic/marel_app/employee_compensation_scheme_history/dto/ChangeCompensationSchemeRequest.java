package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Move an employee onto a different compensation scheme from a given date.
 *
 * <p>There is deliberately no "set the employee's scheme" request that omits a
 * date: a scheme change always has an effective date, because without one the
 * system cannot tell which work the new policy applies to.
 */
@Getter
@Setter
public class ChangeCompensationSchemeRequest {

    @NotNull(message = "Način obračuna je obavezan")
    private Long compensationSchemeId;

    /** Inclusive. Work on this date already uses the new scheme. */
    @NotNull(message = "Datum početka primene je obavezan")
    private LocalDate effectiveFrom;

    private String note;
}
