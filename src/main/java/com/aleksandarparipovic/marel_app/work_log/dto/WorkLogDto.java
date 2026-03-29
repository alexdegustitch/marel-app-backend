package com.aleksandarparipovic.marel_app.work_log.dto;

import java.math.BigDecimal;
import java.time.Instant;

public interface WorkLogDto {

    Long getId();

    Long getShiftId();

    Long getOperationId();
    String getOperationName();

    Long getProductionOrderId();
    String getProductionOrderName();

    Long getProductId();
    String getProductName();

    Instant getStartAt();
    Instant getEndAt();

    Integer getDurationMin();

    Integer getQuantity();
    Integer getScrap();

    String getNote();

    BigDecimal getHourlyOutput();

    Long getWorkCodeCategoryId();
    Boolean getIsActive();
}
