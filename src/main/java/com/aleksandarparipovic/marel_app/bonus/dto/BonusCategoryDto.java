package com.aleksandarparipovic.marel_app.bonus.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.*;

@Data
public class BonusCategoryDto {

    private Long id;
    private String categoryNo;
    private String categoryName;
    private BigDecimal bonusAmount;
    private BigDecimal minHours;
    private String description;

    private boolean active;
    private LocalDate validFrom;
    private LocalDate validUntil;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime archivedAt;

    private boolean currentlyValid;
}
