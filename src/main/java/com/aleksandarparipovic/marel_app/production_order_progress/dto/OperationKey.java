package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/** An operation's aggregation key material: its group, when it is in one. */
public record OperationKey(Long operationGroupId, String operationGroupName) {
}
