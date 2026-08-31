package com.aleksandarparipovic.marel_app.sample_order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Same shape as {@link SampleOrderCreateRequest}, minus {@code code} — the order
 * code is immutable once created — and minus the mailing lists, which are not
 * re-chosen on edit: the recipients were snapshotted when the order was made,
 * and attaching or detaching afterwards is its own operation with its own rules.
 */
public record SampleOrderUpdateRequest(
        @NotBlank(message = "Naziv je obavezan") String name,
        /**
         * The whole form is sent on every save, as with every other field here,
         * so null CLEARS the customer rather than leaving it alone.
         */
        Long customerId,
        LocalDate creationDate,
        @NotNull(message = "Rok je obavezan") LocalDate deadlineDate,
        String deadlineNote,
        String note,
        List<@Valid LineItemRequest> lineItems
) {
    public record LineItemRequest(
            @NotNull(message = "Proizvod je obavezan") Long productId,
            String description,
            String note,
            Integer lineOrder,
            @NotNull(message = "Količina je obavezna")
            @Min(value = 1, message = "Količina mora biti veća od nule")
            Integer quantity
    ) {}
}
