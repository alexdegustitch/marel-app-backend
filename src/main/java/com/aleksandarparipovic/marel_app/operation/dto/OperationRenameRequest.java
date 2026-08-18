package com.aleksandarparipovic.marel_app.operation.dto;

import jakarta.validation.constraints.NotBlank;

public record OperationRenameRequest(@NotBlank String operationName) {
}
