package com.aleksandarparipovic.marel_app.employee.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ArchiveEmployeeRequest {

    @NotBlank
    private String password;
}

