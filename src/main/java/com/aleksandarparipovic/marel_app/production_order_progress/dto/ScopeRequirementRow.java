package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/**
 * One row of what an order's agreed scope asks for, straight from the database:
 * one operation of one line, with the quantity that line ordered.
 *
 * <p>Only lines whose scope was actually SUBMITTED are read, and only the
 * operations the processor marked as needed — a draft is not an agreement, and
 * an operation marked "not needed" is a decision, not a requirement.
 */
public interface ScopeRequirementRow {
    Long getOrderId();
    Long getLineItemId();
    Long getProductId();
    String getProductName();
    Long getOperationId();
    String getOperationName();
    Integer getUnitsPerProduct();
    Integer getLineQuantity();
}
