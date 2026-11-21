package com.aleksandarparipovic.marel_app.shift.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * State one break of a shift, from a date — a default window, an employee's
 * own window, or (employee only) that the employee does not take that break
 * at all ({@code suppressed}; times must then be absent).
 */
@Getter
@Setter
public class ChangeShiftBreakRequest {

    @NotNull(message = "Smena je obavezna.")
    private Long shiftId;

    /** Which break of the shift: 1 = prva pauza, 2 = druga, … */
    @NotNull(message = "Redni broj pauze je obavezan.")
    @Min(value = 1, message = "Redni broj pauze počinje od 1.")
    private Integer breakNo;

    /** Employee only: this person does not take that default break. */
    private boolean suppressed;

    /** Required unless suppressed. */
    private LocalTime startTime;

    /** May be before startTime — the break then crosses midnight. Required unless suppressed. */
    private LocalTime endTime;

    @NotNull(message = "Datum \"važi od\" je obavezan.")
    private LocalDate validFrom;

    /** Inclusive; null = until further notice. */
    private LocalDate validTo;

    @Size(max = 255, message = "Napomena može imati najviše 255 znakova.")
    private String note;
}
