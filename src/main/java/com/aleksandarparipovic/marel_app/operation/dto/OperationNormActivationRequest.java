package com.aleksandarparipovic.marel_app.operation.dto;

/** Why an existing norm from the history is being put back in force. Optional. */
public record OperationNormActivationRequest(String reason) {
}
