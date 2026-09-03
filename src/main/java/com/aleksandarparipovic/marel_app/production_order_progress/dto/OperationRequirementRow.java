package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/** What one order's agreed scope asks of one operation. */
public interface OperationRequirementRow {
    Long getOrderId();
    Long getRequiredPieces();
}
