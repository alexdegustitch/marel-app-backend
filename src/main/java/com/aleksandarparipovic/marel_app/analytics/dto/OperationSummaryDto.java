package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Page 5 — Efikasnost operacija. One row per operation, and nothing under it.
 *
 * <p>The product is carried as CONTEXT, not as a grain: an operation belongs to exactly one,
 * so it adds no rows — but operation NAMES repeat across products ("Brušenje" exists once per
 * product), and without it a row cannot be told from another with the same name.
 */
@Data
@AllArgsConstructor
public class OperationSummaryDto {
    private Long operationId;
    private String operationName;
    private Long productId;
    private String productName;

    private Long sumQuantity;
    private Long sumScrap;
    private Long sumDurationMin;

    private BigDecimal avgPerformancePct;
    private BigDecimal defectPct;
}
