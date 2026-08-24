package com.aleksandarparipovic.marel_app.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailChangeConfirmRequest {

    @NotBlank(message = "Unesite kod iz mejla.")
    private String code;
}
