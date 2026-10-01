package com.aleksandarparipovic.marel_app.performance_mark.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the šifarnik form submits, create and edit alike. On edit the service
 * reads validFrom as the versioning decision: the same date as the open
 * version's start corrects it in place, a later one closes it and opens a new
 * version. (Named Upsert… — PayrollRunItemController already owns an unrelated
 * PerformanceMarkRequest.)
 */
@Getter
@Setter
@NoArgsConstructor
public class UpsertPerformanceMarkRequest {

    @NotNull(message = "Ocena je obavezna.")
    @Digits(integer = 3, fraction = 2, message = "Ocena može imati najviše 3 cifre i 2 decimale.")
    private BigDecimal mark;

    @NotNull(message = "Iznos je obavezan.")
    @Digits(integer = 10, fraction = 2, message = "Iznos može imati najviše 2 decimale.")
    private BigDecimal amount;

    /** PERCENT or RSD_PER_HOUR; parsed and refused with a Serbian message in the service. */
    @NotBlank(message = "Jedinica iznosa je obavezna.")
    private String amountUnit;

    @NotNull(message = "Datum „važi od“ je obavezan.")
    private LocalDate validFrom;

    /** Inclusive; null = važi bez roka. */
    private LocalDate validTo;

    @Size(max = 255, message = "Napomena je predugačka (najviše 255 znakova).")
    private String note;
}
