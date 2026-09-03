package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/** Pieces and scrap recorded against one order for one operation. */
public interface OperationOutputRow {
    Long getOrderId();
    Long getOperationId();
    Long getDonePieces();
    Long getScrapPieces();
}
