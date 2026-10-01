package com.aleksandarparipovic.marel_app.performance_mark;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.performance_mark.dto.PerformanceMarkAdminDto;
import com.aleksandarparipovic.marel_app.performance_mark.dto.UpsertPerformanceMarkRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The šifarnik's administration of performance marks (ocene).
 *
 * <p>The versioning rule is V57's, the same one work-code categories and shift
 * defaults follow: a change to a CALCULATION value (amount, unit, the mark
 * itself) never overwrites the row an old obračun read. The form's "važi od"
 * decides — the same date as the open version's start is a CORRECTION in
 * place (the mark never really carried the mistyped value), a later date
 * closes the open version the day before and opens a new one. The note is
 * cosmetic and always edits in place; setting "važi do" on an open version
 * simply ends the mark without a successor.
 *
 * <p>Archiving is for a row that should never have existed, signed with the
 * caller's re-typed password; restore undoes it unless the period has since
 * been reoccupied.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceMarkAdminService {

    private static final ZoneId BELGRADE = ZoneId.of("Europe/Belgrade");

    private final PerformanceMarkRepository repository;
    private final CurrentUserService currentUserService;
    private final PasswordConfirmationService passwordConfirmation;

    /** The payroll picker: the versions in force on {@code date}, one per mark. */
    @Transactional(readOnly = true)
    public List<com.aleksandarparipovic.marel_app.performance_mark.dto.PerformanceMarkOptionDto> optionsOn(LocalDate date) {
        return repository.findValidOn(date).stream()
                .map(com.aleksandarparipovic.marel_app.performance_mark.dto.PerformanceMarkOptionDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PerformanceMarkAdminDto> listAll() {
        LocalDate today = LocalDate.now(BELGRADE);
        return repository.findAllByOrderByMarkAscValidFromDesc().stream()
                .map(p -> PerformanceMarkAdminDto.from(p, today))
                .toList();
    }

    @Transactional
    public PerformanceMarkAdminDto create(UpsertPerformanceMarkRequest request) {
        PerformanceMarkAmountUnit unit = parseUnit(request.getAmountUnit());
        requireValidDates(request.getValidFrom(), request.getValidTo());
        requireNoOverlap(request.getMark(), request.getValidFrom(), request.getValidTo(), null);

        PerformanceMark saved = repository.saveAndFlush(PerformanceMark.builder()
                .mark(request.getMark())
                .amount(request.getAmount())
                .amountUnit(unit)
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(trimmedNote(request))
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.info("Performance mark {} created: {} {} from {}",
                saved.getMark(), saved.getAmount(), unit, saved.getValidFrom());
        return PerformanceMarkAdminDto.from(saved, LocalDate.now(BELGRADE));
    }

    @Transactional
    public PerformanceMarkAdminDto update(Long id, UpsertPerformanceMarkRequest request) {
        PerformanceMark row = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ocena ne postoji: " + id));
        if (row.getArchivedAt() != null) {
            throw new ConflictException("Arhivirana verzija se ne menja — prvo je vratite.");
        }
        if (row.getValidTo() != null) {
            throw new ConflictException(
                    "Zatvorena verzija je istorija i ne menja se. Izmenite otvorenu verziju ocene.");
        }

        PerformanceMarkAmountUnit unit = parseUnit(request.getAmountUnit());
        requireValidDates(request.getValidFrom(), request.getValidTo());

        boolean valuesChanged = row.getMark().compareTo(request.getMark()) != 0
                || row.getAmount().compareTo(request.getAmount()) != 0
                || row.getAmountUnit() != unit;
        boolean sameDay = request.getValidFrom().equals(row.getValidFrom());

        LocalDate today = LocalDate.now(BELGRADE);

        if (sameDay || !valuesChanged) {
            // A correction in place — the mark never really carried the old
            // value — or a cosmetic edit (note, an end date) of the open row.
            row.setMark(request.getMark());
            row.setAmount(request.getAmount());
            row.setAmountUnit(unit);
            row.setValidTo(request.getValidTo());
            row.setNote(trimmedNote(request));
            requireNoOverlap(row.getMark(), row.getValidFrom(), row.getValidTo(), row.getId());
            return PerformanceMarkAdminDto.from(repository.saveAndFlush(row), today);
        }

        if (request.getValidFrom().isBefore(row.getValidFrom())) {
            throw new IllegalArgumentException(
                    "Nova verzija mora da počne posle početka trenutne (" + row.getValidFrom() + ").");
        }

        // Close, flush, then open: within one flush Hibernate runs inserts
        // first, and ex_pm_no_overlap would refuse the new open window while
        // the old one is still open.
        row.setValidTo(request.getValidFrom().minusDays(1));
        repository.saveAndFlush(row);

        requireNoOverlap(request.getMark(), request.getValidFrom(), request.getValidTo(), null);
        PerformanceMark next = repository.saveAndFlush(PerformanceMark.builder()
                .mark(request.getMark())
                .amount(request.getAmount())
                .amountUnit(unit)
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(trimmedNote(request))
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.info("Performance mark {} re-versioned: {} {} from {} (previous row {} closed at {})",
                next.getMark(), next.getAmount(), unit, next.getValidFrom(), row.getId(), row.getValidTo());
        return PerformanceMarkAdminDto.from(next, today);
    }

    /** Archive, signed with the caller's re-typed password. */
    @Transactional
    public void archive(Long id, String password, Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);
        PerformanceMark row = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ocena ne postoji: " + id));
        row.setArchivedAt(OffsetDateTime.now());
        row.setArchivedBy(currentUserService.getCurrentUserId());
        repository.save(row);
    }

    @Transactional
    public void restore(Long id) {
        PerformanceMark row = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ocena ne postoji: " + id));
        if (row.getArchivedAt() == null) {
            return;
        }
        // The period may have been reoccupied while this row sat archived; a
        // silent constraint violation would read as a broken screen.
        requireNoOverlap(row.getMark(), row.getValidFrom(), row.getValidTo(), row.getId());
        row.setArchivedAt(null);
        row.setArchivedBy(null);
        repository.save(row);
    }

    private void requireNoOverlap(BigDecimal mark, LocalDate from, LocalDate to, Long excludeId) {
        // Sentinels instead of nulls — see the repository note. Year 9999, not
        // LocalDate.MAX: the JDBC driver refuses a date Postgres cannot hold.
        List<PerformanceMark> clash = repository.findOverlapping(
                mark, from, to != null ? to : LocalDate.of(9999, 12, 31), excludeId != null ? excludeId : -1L);
        if (!clash.isEmpty()) {
            PerformanceMark c = clash.get(0);
            throw new ConflictException(
                    "Ocena " + c.getMark().stripTrailingZeros().toPlainString()
                            + " već ima verziju koja pokriva taj period (od " + c.getValidFrom()
                            + (c.getValidTo() != null ? " do " + c.getValidTo() : ", bez roka") + ").");
        }
    }

    private static PerformanceMarkAmountUnit parseUnit(String raw) {
        try {
            return PerformanceMarkAmountUnit.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Jedinica iznosa mora biti PERCENT (procenat satnice) ili RSD_PER_HOUR (dinara po satu).");
        }
    }

    private static void requireValidDates(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("Datum „važi do“ ne može biti pre datuma „važi od“.");
        }
    }

    private static String trimmedNote(UpsertPerformanceMarkRequest request) {
        String note = request.getNote();
        return note == null || note.isBlank() ? null : note.trim();
    }
}
