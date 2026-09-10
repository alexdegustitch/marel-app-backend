package com.aleksandarparipovic.marel_app.work_shift.dto;

import java.time.LocalDate;

/**
 * What a range archive would touch — or, after the fact, what it touched:
 * how many shifts, and the first and last date among them.
 */
public record ArchiveRangeSummary(int shifts, LocalDate firstDate, LocalDate lastDate) {
}
