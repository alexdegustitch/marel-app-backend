package com.aleksandarparipovic.marel_app.operation.dto;

/**
 * One bucket of this operation's output.
 *
 * <p>{@code period} is ISO text and says its own granularity: "2026-08" for a
 * month, "2026-08-11" for a day. A month of work is read day by day — one bar
 * for a whole month answers nothing — while longer ranges are read by month.
 */
public record OperationOutputPointDto(
        String period,
        long quantity,
        long scrap
) {
}
