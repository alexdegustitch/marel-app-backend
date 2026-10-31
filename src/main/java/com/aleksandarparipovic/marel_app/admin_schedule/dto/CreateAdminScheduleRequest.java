package com.aleksandarparipovic.marel_app.admin_schedule.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * What both entry forms submit: the day modal sends one date, the range modal
 * sends the expanded list (the frontend already knows which weekdays the user
 * ticked, so the server takes dates rather than re-deriving them from a range
 * plus flags). One entry is created per date.
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateAdminScheduleRequest {

    @NotNull(message = "Administrator je obavezan.")
    private Long userId;

    @NotNull(message = "Smena je obavezna.")
    private Long shiftId;

    @NotEmpty(message = "Bar jedan datum je obavezan.")
    @Size(max = 366, message = "Najviše 366 dana odjednom.")
    private List<LocalDate> dates;

    /** Both or neither — a window inside the shift; null pair = the whole shift. */
    private LocalTime startTime;

    private LocalTime endTime;

    @Size(max = 255, message = "Napomena je predugačka (najviše 255 znakova).")
    private String note;
}
