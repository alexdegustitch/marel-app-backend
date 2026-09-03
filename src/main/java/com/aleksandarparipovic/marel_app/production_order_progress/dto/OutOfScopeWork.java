package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/**
 * Pieces recorded against this order for an operation its agreed scope does not
 * ask for.
 *
 * <p>Reported rather than discarded. It is real work with no denominator, so it
 * cannot enter a percentage, but a screen that silently ignored it would hide
 * either a mis-keyed order on a work log or a scope that needs revising. There
 * are already such logs in the database.
 */
public record OutOfScopeWork(
        Long operationId,
        String operationName,
        Long productId,
        String productName,
        long donePieces
) {
}
