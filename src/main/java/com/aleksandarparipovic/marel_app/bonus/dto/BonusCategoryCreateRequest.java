package com.aleksandarparipovic.marel_app.bonus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A new (or edited) bonus category, as the supervisor enters it: a code, a name,
 * an amount and the dates it is valid for. No {@code minHours} — the minimum-hours
 * threshold is legacy logic this form deliberately does not offer.
 */
@Getter
@Setter
public class BonusCategoryCreateRequest {

    @NotBlank(message = "Kod je obavezan.")
    @Size(max = 50, message = "Kod je predugačak.")
    private String categoryNo;

    @NotBlank(message = "Naziv je obavezan.")
    @Size(max = 255, message = "Naziv je predugačak.")
    private String categoryName;

    @NotNull(message = "Iznos je obavezan.")
    @Positive(message = "Iznos mora biti veći od 0.")
    private BigDecimal bonusAmount;

    @NotNull(message = "Datum početka važenja je obavezan.")
    private LocalDate validFrom;

    /** Optional: an open-ended bonus has no end date. */
    private LocalDate validUntil;

    /**
     * Edit only: the date a CHANGED amount takes effect (V69 versioning).
     * Empty = today. The same date as the current spell's start corrects it in
     * place; a later date closes it and opens a new spell. Ignored when the
     * amount did not change, and on create.
     */
    private LocalDate amountEffectiveFrom;

    @Size(max = 255, message = "Opis je predugačak.")
    private String description;
}
