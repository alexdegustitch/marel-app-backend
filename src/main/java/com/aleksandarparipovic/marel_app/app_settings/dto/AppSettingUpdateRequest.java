package com.aleksandarparipovic.marel_app.app_settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
public class AppSettingUpdateRequest {

    @NotBlank
    private String settingKey;

    /** numeric | text | boolean */
    @NotBlank
    private String valueType;

    private BigDecimal settingValueNumeric;
    private String settingValueText;
    private Boolean settingValueBoolean;

    private Boolean affectsPayroll;

    private String description;

    @NotNull
    private OffsetDateTime validFrom;

    private OffsetDateTime validUntil;
}

