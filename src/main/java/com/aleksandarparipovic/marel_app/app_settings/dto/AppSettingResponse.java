package com.aleksandarparipovic.marel_app.app_settings.dto;

import com.aleksandarparipovic.marel_app.app_settings.AppSetting;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class AppSettingResponse {

    private final Long id;
    private final String settingKey;
    private final String valueType;
    private final Boolean affectsPayroll;
    private final String description;
    private final String displayText;
    private final OffsetDateTime validFrom;
    private final OffsetDateTime validUntil;
    private final String settingValueText;
    private final BigDecimal settingValueNumeric;
    private final Boolean settingValueBoolean;
    private final String unit;
    private final OffsetDateTime createdAt;

    /** Who inserted this version, resolved from the audit trail when it knows. */
    private String createdByName;

    public AppSettingResponse(AppSetting s) {
        this.id = s.getId();
        this.settingKey = s.getSettingKey();
        this.valueType = s.getValueType();
        this.affectsPayroll = s.getAffectsPayroll();
        this.description = s.getDescription();
        this.displayText = s.getDisplayText();
        this.validFrom = s.getValidFrom();
        this.validUntil = s.getValidUntil();
        this.settingValueText = s.getSettingValueText();
        this.settingValueNumeric = s.getSettingValueNumeric();
        this.settingValueBoolean = s.getSettingValueBoolean();
        this.unit = s.getUnit();
        this.createdAt = s.getCreatedAt();
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }
}
