package com.aleksandarparipovic.marel_app.monthly_scrap.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * One scrap row as the screen holds it — used for both create and replace.
 *
 * <p><b>Whole row, not a patch.</b> The order is optional AND clearable, and a
 * patch cannot tell "leave the order alone" apart from "there is no order" —
 * both arrive as null. The screen already has all five values in hand, so it
 * sends all five and the ambiguity never exists. Editing one cell is a full
 * save of a five-field row, which is cheaper than a tri-state.
 *
 * <p>The PERIOD is not here. It is the month the screen is showing, taken from
 * the URL by the controller, so a row can never be filed under a month the user
 * is not looking at.
 */
@Getter
@Setter
public class MonthlyScrapSaveRequest {

    @NotNull(message = "Proizvod je obavezan.")
    private Long productId;

    @NotNull(message = "Operacija je obavezna.")
    private Long operationId;

    /** Optional — scrap is often found without a known order. */
    private Long productionOrderId;

    @NotNull(message = "Broj komada je obavezan.")
    @Min(value = 1, message = "Broj komada mora biti veći od 0.")
    private Integer quantity;

    private String note;
}
