package com.aleksandarparipovic.marel_app.sample_order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * A new nalog za izradu uzoraka.
 *
 * <p>Shaped like a production order's create request minus everything samples do
 * not have: no flags, no list of delivery windows, and one quantity per line
 * instead of a dated series. Those absences are the feature, not an oversight —
 * a sample run goes out once, and offering a second delivery window would be
 * offering to describe something that never happens.
 */
public record SampleOrderCreateRequest(
        @NotBlank(message = "Šifra je obavezna") String code,
        @NotBlank(message = "Naziv je obavezan") String name,
        /** Optional: null means the samples are for nobody outside — an internal trial. */
        Long customerId,
        /** The date the order is written. Defaults to today when it is not sent. */
        LocalDate creationDate,
        /** The rok. One date, and required — the database will not hold an order without it. */
        @NotNull(message = "Rok je obavezan") LocalDate deadlineDate,
        /** The rok in words, beside the date rather than instead of it. */
        String deadlineNote,
        /** A napomena about the whole order. */
        String note,
        List<@Valid LineItemRequest> lineItems,
        /**
         * Mailing lists to attach as the order is created, snapshotting their
         * members into its recipients in the SAME transaction. Empty or null
         * means the order starts with nobody to tell.
         */
        List<Long> mailingListIds
) {
    public record LineItemRequest(
            @NotNull(message = "Proizvod je obavezan") Long productId,
            /** The opis the shop floor works from. */
            String description,
            String note,
            Integer lineOrder,
            /**
             * One number, not a list. The database refuses zero or less
             * ({@code chk_sample_order_line_items_quantity_valid}), so the same
             * rule is stated here where it can be answered in words.
             */
            @NotNull(message = "Količina je obavezna")
            @Min(value = 1, message = "Količina mora biti veća od nule")
            Integer quantity
    ) {}
}
