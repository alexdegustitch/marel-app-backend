package com.aleksandarparipovic.marel_app.employee_work_category;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class ChangeWorkCategoryRequest {

    @NotNull
    private Long workCodeCategoryId;

    @NotNull
    private LocalDate validFrom;

    /**
     * Optional end. Null leaves the spell open — the ordinary case, "they work
     * in this from now on".
     */
    private LocalDate validTo;

    private String note;
}
