package com.aleksandarparipovic.marel_app.employee_bonus.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class ChangeBonusRequest {

    @NotNull
    private Long bonusCategoryId;

    @NotNull
    private LocalDate validFrom;

    /**
     * Optional end. When set, the PREVIOUS category resumes the day after — a
     * closed range is a temporary move, not a permanent one.
     */
    private LocalDate validTo;
}
