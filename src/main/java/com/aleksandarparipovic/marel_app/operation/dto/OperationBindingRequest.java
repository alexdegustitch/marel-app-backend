package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.Data;

/** The one value an operation is bound to; null clears the binding. */
@Data
public class OperationBindingRequest {
    private Long variantAttributeOptionId;
}
