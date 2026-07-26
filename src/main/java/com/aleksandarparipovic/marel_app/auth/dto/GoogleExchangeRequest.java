package com.aleksandarparipovic.marel_app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleExchangeRequest {

    @NotBlank(message = "Code is required")
    private String code;
}
