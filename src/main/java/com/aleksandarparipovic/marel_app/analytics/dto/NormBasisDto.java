package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one operation's norm is, and what the recorded work says it could be.
 *
 * <p>A norm is pieces per hour, so the candidate is the throughput of everything the report
 * currently has standing for that operation — SUM(quantity) / SUM(hours), duration-weighted
 * by construction rather than by averaging each row's own rate.
 *
 * <p>The current norm and the date it was signed off travel with it so the two numbers can be
 * read side by side without a second request that could answer about a different operation.
 */
@Data
@AllArgsConstructor
public class NormBasisDto {
    private Long operationId;
    private String operationName;

    /** The norm in force, as pieces per hour. Null for an operation that has never had one. */
    private Integer currentNorm;
    /** When that norm was signed off. */
    private LocalDate normDate;

    private Long sumQuantity;
    private Long sumDurationMin;

    /** The candidate: what the filtered work actually produced, per hour. */
    private BigDecimal avgPerHour;
}
