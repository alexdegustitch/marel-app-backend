package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

// Row shape shared by pages 1 (Proizvod-operacija) and 4 (Efikasnost proizvoda) — identical
// output columns per the spec. The sum* fields are hidden raw components: the frontend needs
// them to correctly recompute a duration-weighted subtotal when grouping rows by product,
// because averaging pre-computed percentages (avgPerformancePct) across operations would be
// mathematically wrong.
@Data
@AllArgsConstructor
public class ProductOperationSummaryDto {
    private Long productId;
    private String productName;
    private Long operationId;
    private String operationName;

    private Long sumQuantity;
    private Long sumScrap;
    private Long sumDurationMin;

    private BigDecimal avgPerHour;
    private BigDecimal defectPct;
    private BigDecimal avgPerformancePct;

    // hidden components, used only by the frontend's custom aggregationFn to re-derive a
    // correct weighted subtotal across multiple operations of the same product
    private BigDecimal sumWeightedPerformance;
    private Long sumPerformanceDurationMin;
}
