package com.aleksandarparipovic.marel_app.operation.dto;

/** Null clears the value; a number must be at least 1 — the service checks. */
public record OperationUnitsChangeRequest(Integer unitsPerProduct) {
}
