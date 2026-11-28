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

    /**
     * V77 — the group of interchangeable machines the operation belongs to,
     * read LIVE from the catalogue (a LEFT join; null for the ordinary single
     * operation). Live on purpose: razrada names the default member, and work
     * on ANY member must land in the same bucket even when the group was
     * formed after the razrada was written.
     */
    Long getOperationGroupId();
    String getOperationGroupName();

    /** V78 — which variant the line ordered; null on a product without variants. */
    Long getProductVariantId();
    String getVariantLabel();
}
