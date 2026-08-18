package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One work log behind a note search — the raw rows a summary row was built from, listed so a
 * note can be read in the context it was written in: the day, the person, the stretch of time,
 * and what came out of it.
 *
 * <p>There is no end-time column on the facts: a log records when it started and how long it
 * lasted, so the end is stated as start + {@code durationMin} where it is displayed.
 */
@Data
@AllArgsConstructor
public class NoteOccurrenceDto {
    private Long workLogId;
    private LocalDate workDate;
    private String shiftCode;
    private String employeeName;
    private String productName;
    private String operationName;
    private LocalTime startTime;
    private Integer durationMin;
    private Long quantity;
    private Long scrap;
    private String note;
}
