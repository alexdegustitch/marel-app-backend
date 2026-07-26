package com.aleksandarparipovic.marel_app.user_saved_view.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SavedViewRequest {

    @NotBlank(message = "Naziv prikaza je obavezan")
    @Size(max = 150, message = "Naziv može imati najviše 150 karaktera")
    private String name;

    /**
     * Plain Map/List, not JsonNode: the HTTP layer is Jackson 3 and cannot bind the
     * Jackson 2 node type the entity uses. See JsonPayloads.
     * Shape and size are validated in the service.
     */
    private java.util.Map<String, Object> filters;
    private java.util.List<Object> sorting;
    private java.util.List<Object> columns;

    private Boolean isDefault;
}
