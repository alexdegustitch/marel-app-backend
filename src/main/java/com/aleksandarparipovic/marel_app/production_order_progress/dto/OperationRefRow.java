package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/** An operation and the product it belongs to, as read. */
public interface OperationRefRow {
    Long getOperationId();
    String getOperationName();
    Long getProductId();
    String getProductName();
}
