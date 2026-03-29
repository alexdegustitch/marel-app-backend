package com.aleksandarparipovic.marel_app.work_log.dto;

import java.math.BigDecimal;
import java.time.Instant;

public interface WorkLogPreviewDto {
    Long getId();

    Long getShiftId();

    Long getOperationId();
    String getOperationName();

    Instant getStartAt();
    Instant getEndAt();

    Integer getDurationMin();

}
