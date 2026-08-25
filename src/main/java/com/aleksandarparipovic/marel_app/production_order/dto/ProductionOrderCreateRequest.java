package com.aleksandarparipovic.marel_app.production_order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record ProductionOrderCreateRequest(
        @NotBlank String code,
        @NotBlank String name,
        /** Optional: null means the order is internal, not for an outside customer. */
        Long customerId,
        String note,
        Boolean testingRequired,
        LocalDate creationDate,
        LocalDate orderDate,
        String deliveryDeadline,
        Boolean isHighPriority,
        Boolean isAnnounced,
        Boolean hasSuccessiveDeliveries,
        List<@Valid DeadlineRequest> deadlines,
        List<@Valid LineItemRequest> lineItems,
        /**
         * Mailing lists to attach as the order is created, snapshotting their
         * members into its recipients in the SAME transaction. Empty or null
         * means the order starts with nobody to tell; lists can still be
         * attached afterwards from the order itself.
         */
        List<Long> mailingListIds
) {
    public record DeadlineRequest(
            LocalDate deadlineDateFrom,
            @NotNull LocalDate deadlineDateTo,
            Integer quantity
    ) {}

    public record LineItemRequest(
            @NotNull Long productId,
            String description,
            String note,
            Integer lineOrder,
            List<@Valid QuantityRequest> quantities
    ) {}

    public record QuantityRequest(
            @NotNull @Min(1) Integer quantity,
            LocalDate deliveryDeadline
    ) {}
}
