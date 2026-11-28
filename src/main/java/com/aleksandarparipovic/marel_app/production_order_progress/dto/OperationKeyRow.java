package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/**
 * Which aggregation key an operation belongs to: its group when it is in one,
 * itself otherwise. Fetched for every operation seen in requirements or
 * output, so the work-log sum can stay index-only and the mapping happens in
 * Java (COALESCE(group, operation) — SPEC 5.6).
 */
public interface OperationKeyRow {
    Long getOperationId();
    Long getOperationGroupId();
    String getOperationGroupName();
}
