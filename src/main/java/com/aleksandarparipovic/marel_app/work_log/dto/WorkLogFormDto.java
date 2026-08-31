package com.aleksandarparipovic.marel_app.work_log.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class WorkLogFormDto {
    private Long id;
    private Long workShiftId;
    private Long productionOrderId;
    private Long productId;
    private Long operationId;
    private String startAt;
    private String endAt;
    private Integer scrap;
    private Integer quantity;
    private Long workCodeCategoryId;
    private String note;
    private Boolean isActive;
    private String workDate;
    private BigDecimal performanceRate;
    private BigDecimal approvedPerformanceRate;
    private BigDecimal normMultiplierSnapshot;

    /**
     * The coefficient somebody typed over the resolved one, or null to go back to
     * it. Unlike the snapshot above this IS read from the request: it is the one
     * coefficient a person is allowed to decide, and who decided it is recorded.
     */
    private BigDecimal normMultiplierManual;
}