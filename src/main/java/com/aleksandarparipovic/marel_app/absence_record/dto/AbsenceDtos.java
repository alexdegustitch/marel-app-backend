package com.aleksandarparipovic.marel_app.absence_record.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** The shapes the absence screens exchange. Grouped: none carries behaviour. */
public final class AbsenceDtos {

    private AbsenceDtos() {
    }

    public record AbsenceCreateRequest(
            @NotNull Long workShiftId,
            @NotNull Long workCodeCategoryId,
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt,
            String note) {
    }

    /** Which overtime day paid for part of an absence, and how much of it. */
    public record CompensationSourceDto(LocalDate workDate, int minutes) {
    }

    public record AbsenceRecordDto(
            Long id,
            Long workShiftId,
            Long workCodeCategoryId,
            String categoryNo,
            String categoryName,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            int absenceMinutes,
            int compensatedMinutes,
            /** NO, ND, or null for a paid absence that takes no part in the bank. */
            String outcome,
            String note,
            List<CompensationSourceDto> compensatedBy) {
    }

    /**
     * A stretch of the shift nobody recorded work for.
     *
     * <p>Offered, not assumed: the gap may be a break, a forgotten entry or a
     * genuine absence, and only the person looking at it knows which.
     */
    public record SuggestedAbsenceDto(OffsetDateTime startAt, OffsetDateTime endAt, int minutes) {
    }

    public record OvertimeDayDto(LocalDate workDate, int overtimeMinutes, int spentMinutes) {
    }

    /** One month's bank: what was earned, what it bought, what is left. */
    public record OvertimeBankDto(
            int earnedMinutes,
            int spentMinutes,
            int remainingMinutes,
            List<OvertimeDayDto> days) {
    }
}
