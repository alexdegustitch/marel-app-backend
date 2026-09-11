package com.aleksandarparipovic.marel_app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/** Give an employee their own hours for a shift, from a date. */
@Getter
@Setter
public class ChangeShiftTimeRequest {

    @NotNull(message = "Smena je obavezna.")
    private Long shiftId;

    @NotNull(message = "Vreme početka je obavezno.")
    private LocalTime startTime;

    /** May be before startTime — the shift then runs over midnight. */
    @NotNull(message = "Vreme kraja je obavezno.")
    private LocalTime endTime;

    @NotNull(message = "Datum \"važi od\" je obavezan.")
    private LocalDate validFrom;

    /** Inclusive; null = until further notice. */
    private LocalDate validTo;

    @Size(max = 255, message = "Napomena može imati najviše 255 znakova.")
    private String note;
}
