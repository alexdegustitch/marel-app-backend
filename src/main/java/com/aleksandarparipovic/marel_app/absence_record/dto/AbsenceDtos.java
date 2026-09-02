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
            /** The shift's date. The per-shift screen knows it; a monthly list cannot. */
            LocalDate workDate,
            Long workCodeCategoryId,
            String categoryNo,
            String categoryName,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            int absenceMinutes,
            int compensatedMinutes,
            /** NO, ND, or null for a paid absence that takes no part in the bank. */
            String outcome,
            /**
             * ND when somebody entered this day AS a neradni dan.
             *
             * <p>Read together with {@code outcome}: requested ND with outcome NO
             * is the day that was asked for and the bank could not pay for.
             */
            String requestedOutcome,
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

    /** An absence somebody may choose. ND is never among them. */
    public record AbsenceCategoryDto(Long id, String categoryNo, String categoryName, boolean paid) {
    }

    public record OvertimeDayDto(LocalDate workDate, int overtimeMinutes, int spentMinutes) {
    }

    /**
     * A month of absences with the bank that decided them.
     *
     * <p>The two together on purpose: an absence's NO or ND only makes sense
     * beside the hours that were or were not there to cover it.
     */
    public record MonthlyAbsencesDto(List<AbsenceRecordDto> absences, OvertimeBankDto bank) {
    }

    /** One month's bank: what was earned, what it bought, what is left. */
    public record OvertimeBankDto(
            int earnedMinutes,
            int spentMinutes,
            int remainingMinutes,
            List<OvertimeDayDto> days) {
    }
}
