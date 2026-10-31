package com.aleksandarparipovic.marel_app.admin_schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * The edit form's submit: the shift, the window and the note are replaced with
 * what the form holds (a cleared window arrives as a null pair and means "the
 * whole shift" again). The date and the person never change — moving an entry
 * is remove-and-create, which keeps each row one person's one day.
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateAdminScheduleRequest {

    @NotNull(message = "Smena je obavezna.")
    private Long shiftId;

    private LocalTime startTime;

    private LocalTime endTime;

    @Size(max = 255, message = "Napomena je predugačka (najviše 255 znakova).")
    private String note;
}
