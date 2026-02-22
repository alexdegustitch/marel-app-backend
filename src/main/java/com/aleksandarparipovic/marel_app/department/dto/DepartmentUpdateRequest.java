package com.aleksandarparipovic.marel_app.department.dto;

import lombok.Data;

@Data
public class DepartmentUpdateRequest {

    // optional – if null, do not change
    private String name;

    private String description;

    // optional – if null, do not change
    private Boolean active;
}
