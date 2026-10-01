package com.aleksandarparipovic.marel_app.performance_mark.dto;

import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMark;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One version row for the šifarnik screen. {@code current} marks the version
 * governing today, {@code editable} the open, live row the form may change —
 * a closed version is history and a future open version is the one to edit.
 */
public record PerformanceMarkAdminDto(
        Long id,
        BigDecimal mark,
        BigDecimal amount,
        String amountUnit,
        LocalDate validFrom,
        LocalDate validTo,
        String note,
        OffsetDateTime archivedAt,
        boolean current,
        boolean editable
) {
    public static PerformanceMarkAdminDto from(PerformanceMark p, LocalDate today) {
        boolean live = p.getArchivedAt() == null;
        boolean current = live
                && !p.getValidFrom().isAfter(today)
                && (p.getValidTo() == null || !p.getValidTo().isBefore(today));
        return new PerformanceMarkAdminDto(
                p.getId(),
                p.getMark(),
                p.getAmount(),
                p.getAmountUnit().name(),
                p.getValidFrom(),
                p.getValidTo(),
                p.getNote(),
                p.getArchivedAt(),
                current,
                live && p.getValidTo() == null
        );
    }
}
