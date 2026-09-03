package com.aleksandarparipovic.marel_app.production_order_progress.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One operation of one product, as the order's agreed scope asks for it and as
 * the floor has actually recorded it.
 *
 * @param unitsPerProduct how many of this operation go into one assembly (KP),
 *                        as the scope decided. When the same product sits on
 *                        more than one line and the two lines were given
 *                        different values, this is their weighted average and
 *                        {@code requiredPieces} remains the exact figure.
 * @param requiredPieces  {@code Σ (line quantity × units per product)} over the
 *                        lines that need it
 * @param donePieces      the pieces recorded against this order and operation
 * @param scrapPieces     recorded beside them; it does NOT reduce donePieces
 *
 * <p>The three figures below are DERIVED rather than stored, and each is
 * annotated so that it reaches the client. Jackson builds a record's JSON from
 * its components alone: without the annotation these methods exist on the
 * server and nowhere else, and a screen reading {@code percent} would find
 * nothing and draw "no razrada" over an operation whose razrada is right beside
 * it. {@code OrderProgressJsonTest} holds that line.
 */
public record OperationProgress(
        Long operationId,
        String operationName,
        BigDecimal unitsPerProduct,
        long requiredPieces,
        long donePieces,
        long scrapPieces
) {

    /**
     * How far this one operation has got, never above 100.
     *
     * <p>Capped because an operation cannot be more than finished: without the
     * cap, 300 pieces of a 100-piece requirement would carry the order's figure
     * past what the other operations have actually done.
     */
    @JsonProperty("percent")
    public BigDecimal percent() {
        if (requiredPieces <= 0) {
            return null;
        }
        return BigDecimal.valueOf(Math.min(donePieces, requiredPieces))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(requiredPieces), 1, RoundingMode.DOWN);
    }

    /** The pieces that count toward the order's figure: never more than asked. */
    @JsonProperty("countedPieces")
    public long countedPieces() {
        return Math.min(donePieces, requiredPieces);
    }

    /** Whether more was recorded than the scope asked for. Worth saying out loud. */
    @JsonProperty("overproduced")
    public boolean overproduced() {
        return requiredPieces > 0 && donePieces > requiredPieces;
    }

    /**
     * How many whole products this operation alone could account for.
     *
     * <p>{@code floor(done ÷ units per product)}, expressed through the totals so
     * that a product spread over several lines with different KP still has one
     * defined answer. With one line, or with equal KP, it is exactly
     * {@code floor(done ÷ KP)}.
     */
    public long wholeProductsSupported(long requiredProducts) {
        if (requiredPieces <= 0 || requiredProducts <= 0) {
            return 0;
        }
        return Math.floorDiv(donePieces * requiredProducts, requiredPieces);
    }
}
