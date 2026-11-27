package com.aleksandarparipovic.marel_app.operation.dto;

/**
 * The slice a picker needs. The V77 fields let the karton offer groups
 * collapsed (default marked, every member selectable) and show the binding
 * badge ("Navrtka: Al") that auto-fills when such an operation is picked.
 */
public record OperationBasicInfoDto(
        Long id,
        String name,
        Integer minNorm,
        Long workCodeCategoryId,
        Long operationGroupId,
        String operationGroupName,
        Boolean groupDefault,
        String bindingAttributeName,
        Long bindingOptionId,
        String bindingOptionValue
) {
}
