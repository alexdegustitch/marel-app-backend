package com.aleksandarparipovic.marel_app.shift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/** Create a shift, or change one from the šifarnik. */
@Getter
@Setter
public class UpsertShiftRequest {

    @NotBlank(message = "Oznaka smene je obavezna.")
    @Size(max = 20, message = "Oznaka smene može imati najviše 20 znakova.")
    private String shiftCode;

    @Size(max = 100, message = "Naziv smene može imati najviše 100 znakova.")
    private String name;

    @NotNull(message = "Vreme početka je obavezno.")
    private LocalTime startTime;

    /** May be before startTime — the shift then runs over midnight. */
    @NotNull(message = "Vreme kraja je obavezno.")
    private LocalTime endTime;

    /**
     * From which date the NEW default hours apply, when an update changes them.
     * Defaults to today. The old hours stay on record as a closed period.
     */
    private LocalDate effectiveFrom;

    private Boolean isActive;
}
