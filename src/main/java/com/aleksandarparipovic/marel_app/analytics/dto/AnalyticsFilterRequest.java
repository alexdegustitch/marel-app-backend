package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Shared filter contract for all 5 analytics endpoints. Each query method reads only the
// fields relevant to its page (documented per-method in AnalyticsQueryRepository); unused
// fields are simply ignored.
@Data
public class AnalyticsFilterRequest {

    private List<LocalDate> dates;
    private LocalDate dateFrom;
    private LocalDate dateTo;

    private List<LocalDate> months;          // first-of-month markers, matched against month_start

    private List<Long> shiftIds;
    private List<Long> productionOrderIds;

    private List<String> notes;              // exact-match multi-select
    private String noteLike;                 // ILIKE '%...%' fragment

    private List<LocalTime> startTimes;
    private LocalTime startTimeFrom;
    private LocalTime startTimeTo;

    private List<Long> productIds;
    private List<Long> operationIds;
    private List<Long> employeeIds;          // page 2/3 only

    // page 2 sidebar only — filters on raw work_log_facts.duration_min (per-log, pre-aggregation)
    private Integer durationMinFrom;
    private Integer durationMinTo;

    /**
     * Page 1 only — the grain the report is asked about: "OPERATION" (default) gives one
     * row per product+operation, "PRODUCT" one row per product, totalled across its
     * operations. It decides what the aggregate bounds below are measured against, which
     * is why it cannot be a frontend-only toggle: a product whose TOTAL output is above
     * a bound may have no single operation above it.
     */
    private String level;

    /**
     * Aggregate bounds, applied as HAVING at whatever grain `level` selects — never to a
     * single work log. They read as "show me rows whose total/average lands in here".
     */
    private Long minQuantity;
    private Long maxQuantity;
    private Long minScrap;
    private Long maxScrap;
    private BigDecimal minAvgPerHour;
    private BigDecimal maxAvgPerHour;
    private BigDecimal minPerformancePct;
    private BigDecimal maxPerformancePct;
    private BigDecimal minDefectPct;
    private BigDecimal maxDefectPct;

    /**
     * Page 1 only — sorting and paging, on the server.
     *
     * <p>They belong here rather than in the client because the report is not a list the
     * client holds: there are 10–15k operations, so "sortiraj po učinku" has to mean "the
     * best across ALL of them", which only the side holding all of them can answer.
     *
     * <p>{@code groupByProduct} keeps the product bands while a measure is sorted — and it
     * also changes what a page IS: a page of PRODUCTS (with all their operations) rather
     * than a page of rows, so a band always carries its product's whole total.
     */
    private String sortBy;
    private String sortDir;                  // "ASC" / "DESC"
    private Integer page;
    private Integer size;
    private Boolean groupByProduct;

    /**
     * Page 2 only — keeps the date -> shift -> product -> operation tree, and with it changes
     * what a page IS: a page of DATES, each arriving with every shift, product, operation and
     * worker recorded on it. A date is therefore never split across two chunks, which is what
     * lets a day's (and a shift's) subtotal be the whole of it rather than the part that
     * happened to land on this page.
     *
     * <p>Its counterpart on page 1 is {@code groupByProduct}; the two are separate fields
     * because they page by different things and a report only ever means one of them.
     */
    private Boolean groupByDate;
}
