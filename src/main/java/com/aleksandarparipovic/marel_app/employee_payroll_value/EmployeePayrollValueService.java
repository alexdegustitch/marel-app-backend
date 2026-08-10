package com.aleksandarparipovic.marel_app.employee_payroll_value;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-employee payroll values and their history.
 *
 * <p>The one rule that shapes everything here: <b>a change of value is appended,
 * never written over the old one.</b> Changing a rate closes the open period and
 * inserts a new one, so a month already calculated keeps the rate that was
 * actually applied to it.
 *
 * <p>The single exception is {@link #setValue}, which corrects the period that
 * starts on the very date given — a typo being fixed, not a rate that changed.
 * Recording that as a second period would assert a raise that never happened.
 * The overwritten value stays recoverable in {@code audit_logs}.
 *
 * <p>Reads are always by DATE — the payroll period — and never by {@code now()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeePayrollValueService {

    private final EmployeePayrollValueHistoryRepository historyRepository;
    private final com.aleksandarparipovic.marel_app.recalc_queue.AffectedMonthsRecalculator recalculator;
    private final EmployeePayrollValueDefinitionRepository definitionRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<EmployeePayrollValueDefinition> definitions() {
        return definitionRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<EmployeePayrollValueHistory> getHistory(Long employeeId, String code) {
        requireEmployee(employeeId);
        return historyRepository.findHistoryFor(employeeId, code);
    }

    /**
     * The numeric value in force for one employee on one date.
     *
     * <p>Empty when there is none. Callers must treat that as "not configured" and
     * decide for themselves — a calculator that turns it into a silent zero is
     * paying somebody nothing without saying so.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> numericValueOn(Long employeeId, String code, LocalDate on) {
        return historyRepository.findInForce(employeeId, code, on)
                .map(EmployeePayrollValueHistory::getNumericValue);
    }

    /**
     * Every value in force for a batch of employees on one date, as
     * {@code employeeId -> definitionCode -> value}.
     *
     * <p>One query for the whole batch. A payroll run resolves hundreds of
     * employees here rather than issuing a lookup per row — the same reasoning as
     * {@code PayrollSchemeScopeService.scopesFor}, and the reason that class is
     * shaped the way it is.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, BigDecimal>> numericValuesOn(Collection<Long> employeeIds,
                                                              LocalDate on) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, BigDecimal>> result = new HashMap<>();
        for (EmployeePayrollValueHistory row : historyRepository.findInForceForEmployees(employeeIds, on)) {
            // NUMERIC ROWS ONLY. Since TRANSPORT_PER_DAY arrived, an employee can
            // have a BOOLEAN value in force and no numeric one — and putting it
            // here mapped its code to null and, worse, created an entry for an
            // employee who has no numeric value at all. "Absent means not
            // configured" is the contract every calculator reads this map by; an
            // employee present with nothing in them breaks it silently.
            if (row.getNumericValue() == null) {
                continue;
            }
            result.computeIfAbsent(row.getEmployee().getId(), id -> new HashMap<>())
                    .put(row.getDefinition().getCode(), row.getNumericValue());
        }
        return result;
    }

    /**
     * The BOOLEAN values that are TRUE for a batch of employees on one date, as
     * {@code employeeId -> set of definition codes}.
     *
     * <p>An absent code means "not configured", exactly as with the numeric
     * values — never false-as-in-decided. A calculator that cannot tell those two
     * apart pays somebody nothing without saying so.
     *
     * <p>One query for the whole batch, same reasoning as
     * {@link #numericValuesOn}.
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<String>> trueFlagsOn(Collection<Long> employeeIds, LocalDate on) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> result = new HashMap<>();
        for (EmployeePayrollValueHistory row : historyRepository.findInForceForEmployees(employeeIds, on)) {
            if (Boolean.TRUE.equals(row.getBooleanValue())) {
                result.computeIfAbsent(row.getEmployee().getId(), id -> new HashSet<>())
                        .add(row.getDefinition().getCode());
            }
        }
        return result;
    }

    /**
     * Give an employee a new value for {@code code}, effective from {@code effectiveFrom}.
     *
     * <p>Closes the period covering that date on the day before, then inserts the
     * new one — both in this transaction, so the pair is never visible half-applied.
     * The row-level lock makes two concurrent changes serialise into a clean
     * sequence rather than one of them dying on {@code ex_epvh_no_overlap} with an
     * error the user cannot act on.
     *
     * <p>{@code effectiveFrom} may also fall BEFORE every existing period. That is
     * not an edge case: the backfill starts transport rates from the first
     * uncalculated month because the real start date is not recorded anywhere, and
     * correcting one employee to "actually from January 2025" is the intended way
     * to fix it. The new period then stops the day before the next one begins, and
     * if that next period already holds the same value it is simply extended
     * backwards rather than split in two.
     */
    @Transactional
    public EmployeePayrollValueHistory changeValue(Long employeeId,
                                                   String code,
                                                   BigDecimal numericValue,
                                                   LocalDate effectiveFrom,
                                                   String note,
                                                   Long changedBy) {
        if (numericValue == null) {
            throw new IllegalArgumentException("Vrednost je obavezna.");
        }
        if (numericValue.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Vrednost ne može biti negativna.");
        }
        EmployeePayrollValueHistory changed = changePeriod(employeeId, code, "NUMERIC", numericValue, null,
                effectiveFrom, note, changedBy);

        // These values PRICE work: a rate or an entitlement changed from a past
        // date leaves the old numbers on payslips already calculated. Locked
        // months are left alone and the change stands regardless — the caller
        // reads recalculationResult() to tell the user which were skipped.
        recalculator.recalculate(
                changed.getEmployee(), effectiveFrom, null,
                "Vrednost " + code + " promenjena od " + effectiveFrom);

        return changed;
    }

    /**
     * The same operation for a BOOLEAN value — an entitlement with a start date.
     *
     * <p>{@code TRANSPORT_PER_DAY} is the first: having it TRUE and in force is
     * what puts an employee on the per-day transport mode, and the date is what
     * every month before it needs in order to pay nothing rather than something
     * (OPEN-15).
     *
     * <p>Shares every line of the period mechanics with {@link #changeValue} —
     * closing the covering period, extending a successor that already says the
     * same thing, bounding a backdated period. Two copies of that logic is two
     * chances to get {@code ex_epvh_no_overlap} wrong.
     */
    @Transactional
    public EmployeePayrollValueHistory changeFlag(Long employeeId,
                                                  String code,
                                                  boolean value,
                                                  LocalDate effectiveFrom,
                                                  String note,
                                                  Long changedBy) {
        EmployeePayrollValueHistory changed = changePeriod(employeeId, code, "BOOLEAN", null, value,
                effectiveFrom, note, changedBy);

        // An entitlement prices work exactly as a rate does — transport is paid or
        // it is not — so a dated change to one has the same consequence for months
        // already calculated. This was missing while changeValue had it, which
        // meant granting or withdrawing transport left the old payslips standing.
        recalculator.recalculate(
                changed.getEmployee(), effectiveFrom, null,
                "Vrednost " + code + " promenjena od " + effectiveFrom);

        return changed;
    }

    /**
     * Correct what a period SAYS, leaving when it applies alone.
     *
     * <p>The history is kept for auditing, so the old row is archived rather than
     * overwritten: what was believed before stays readable, and
     * {@code ex_epvh_no_overlap} already ignores archived rows, so the corrected
     * period can occupy the same dates. An UPDATE would leave no trace that the
     * figure was ever something else.
     *
     * <p>DATES ARE NOT EDITED HERE. Moving a period is removing one and adding
     * another — it changes which months are repriced and how the neighbours are
     * bounded, and the two operations that already do that are the ones to use.
     */
    @Transactional
    public EmployeePayrollValueHistory correctPeriod(Long employeeId,
                                                     Long historyId,
                                                     BigDecimal numericValue,
                                                     Boolean booleanValue,
                                                     String note,
                                                     Long changedBy) {
        EmployeePayrollValueHistory period = livePeriod(employeeId, historyId);

        if ("NUMERIC".equals(period.getValueType())) {
            if (numericValue == null) {
                throw new IllegalArgumentException("Vrednost je obavezna.");
            }
            if (numericValue.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Vrednost ne može biti negativna.");
            }
        } else if (booleanValue == null) {
            throw new IllegalArgumentException("Vrednost je obavezna.");
        }

        LocalDate from = period.getValidFrom();
        LocalDate until = period.getValidUntil();

        period.setArchivedAt(OffsetDateTime.now());
        historyRepository.saveAndFlush(period);

        EmployeePayrollValueHistory corrected = historyRepository.save(
                EmployeePayrollValueHistory.builder()
                        .employee(period.getEmployee())
                        .definition(period.getDefinition())
                        .valueType(period.getValueType())
                        .numericValue("NUMERIC".equals(period.getValueType()) ? numericValue : null)
                        .booleanValue("BOOLEAN".equals(period.getValueType()) ? booleanValue : null)
                        .validFrom(from)
                        .validUntil(until)
                        .note(note != null ? note : period.getNote())
                        .createdBy(changedBy)
                        .build());

        log.info("Employee {} value {} corrected for [{} .. {}] (archived {}, new {})",
                employeeId, period.getDefinition().getCode(), from,
                until == null ? "open" : until, period.getId(), corrected.getId());

        recalculator.recalculate(
                period.getEmployee(), from, until,
                "Ispravljena vrednost " + period.getDefinition().getCode() + " od " + from);

        return corrected;
    }

    /**
     * Take a period out, and leave no hole where it was.
     *
     * <p>A period entered by mistake is not deleted: it is archived, so the
     * record still says somebody once believed it. What its dates covered is
     * handed to a neighbour — the one before it extends forward, or failing that
     * the one after it starts earlier — because a gap would mean the employee has
     * NO value on those days, which is a different statement from the one being
     * withdrawn.
     */
    @Transactional
    public void removePeriod(Long employeeId, Long historyId, Long changedBy) {
        EmployeePayrollValueHistory period = livePeriod(employeeId, historyId);

        LocalDate from = period.getValidFrom();
        LocalDate until = period.getValidUntil();

        List<EmployeePayrollValueHistory> periods =
                historyRepository.lockPeriodsFor(employeeId, period.getDefinition().getId());

        EmployeePayrollValueHistory predecessor = periods.stream()
                .filter(p -> !p.getId().equals(historyId))
                .filter(p -> p.getValidFrom().isBefore(from))
                .max(Comparator.comparing(EmployeePayrollValueHistory::getValidFrom))
                .orElse(null);
        EmployeePayrollValueHistory successor = periods.stream()
                .filter(p -> !p.getId().equals(historyId))
                .filter(p -> p.getValidFrom().isAfter(from))
                .min(Comparator.comparing(EmployeePayrollValueHistory::getValidFrom))
                .orElse(null);

        // Archived first: the neighbour is about to occupy these dates, and the
        // overlap constraint counts the row until it is out of the way.
        period.setArchivedAt(OffsetDateTime.now());
        historyRepository.saveAndFlush(period);

        if (predecessor != null) {
            predecessor.setValidUntil(until);
            historyRepository.saveAndFlush(predecessor);
        } else if (successor != null) {
            successor.setValidFrom(from);
            historyRepository.saveAndFlush(successor);
        }

        log.info("Employee {} value {} period [{} .. {}] removed (history id {}); {}",
                employeeId, period.getDefinition().getCode(), from, until == null ? "open" : until,
                historyId,
                predecessor != null ? "previous period extended"
                        : successor != null ? "next period moved back" : "no neighbour to cover it");

        recalculator.recalculate(
                period.getEmployee(), from, until,
                "Uklonjena vrednost " + period.getDefinition().getCode() + " od " + from);
    }

    /** One period of this employee's, still in force — not somebody else's, not archived. */
    private EmployeePayrollValueHistory livePeriod(Long employeeId, Long historyId) {
        EmployeePayrollValueHistory period = historyRepository.findById(historyId)
                .orElseThrow(() -> new EntityNotFoundException("Period ne postoji: " + historyId));

        // Checked rather than trusted from the path: the id alone would let one
        // employee's period be edited through another employee's URL.
        if (!period.getEmployee().getId().equals(employeeId)) {
            throw new EntityNotFoundException("Period ne pripada ovom zaposlenom.");
        }
        if (period.getArchivedAt() != null) {
            throw new ConflictException("Taj period je već uklonjen.");
        }
        return period;
    }

    /**
     * @param numericValue exactly one of these two is non-null; {@code expectedType}
     * @param booleanValue says which, and the definition must agree.
     */
    private EmployeePayrollValueHistory changePeriod(Long employeeId,
                                                     String code,
                                                     String expectedType,
                                                     BigDecimal numericValue,
                                                     Boolean booleanValue,
                                                     LocalDate effectiveFrom,
                                                     String note,
                                                     Long changedBy) {
        Employee employee = requireEmployee(employeeId);

        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Datum početka primene je obavezan.");
        }

        EmployeePayrollValueDefinition definition = definitionRepository.findByCode(code)
                .orElseThrow(() -> new EntityNotFoundException("Nepoznata vrsta vrednosti: " + code));
        if (!definition.isUsable()) {
            throw new IllegalArgumentException(
                    "Vrsta vrednosti \"" + definition.getName() + "\" nije aktivna.");
        }
        if (!expectedType.equals(definition.getValueType())) {
            throw new IllegalArgumentException(
                    "Vrsta vrednosti \"" + definition.getName() + "\" nije "
                            + ("NUMERIC".equals(expectedType) ? "brojčana" : "logička") + ".");
        }

        List<EmployeePayrollValueHistory> periods =
                historyRepository.lockPeriodsFor(employeeId, definition.getId());

        EmployeePayrollValueHistory sameStart = periods.stream()
                .filter(p -> p.getValidFrom().equals(effectiveFrom))
                .findFirst().orElse(null);
        if (sameStart != null) {
            throw new ConflictException(
                    "Već postoji vrednost koja počinje " + effectiveFrom
                            + ". Obrišite ili arhivirajte taj period pre dodavanja novog.");
        }

        // The period that is in force on that date, if any.
        EmployeePayrollValueHistory covering = periods.stream()
                .filter(p -> p.coversInclusive(effectiveFrom))
                .findFirst().orElse(null);

        // The first period that starts AFTER the new one. It bounds the new period,
        // which is what makes a date before the whole history — "transport actually
        // started in January 2025" — a normal operation rather than a special case.
        EmployeePayrollValueHistory successor = periods.stream()
                .filter(p -> p.getValidFrom().isAfter(effectiveFrom))
                .min(Comparator.comparing(EmployeePayrollValueHistory::getValidFrom))
                .orElse(null);

        if (covering != null && holdsSameValue(covering, numericValue, booleanValue)) {
            throw new ConflictException("Zaposleni već ima tu vrednost na datum " + effectiveFrom + ".");
        }

        // Backdating onto a successor that already holds this value extends that
        // period instead of splitting it. Two adjacent periods with the same number
        // say nothing a single one does not, and the split would misrepresent a
        // correction as a change of rate.
        if (covering == null && successor != null
                && holdsSameValue(successor, numericValue, booleanValue)) {
            successor.setValidFrom(effectiveFrom);
            successor.setNote(note != null ? note : successor.getNote());
            EmployeePayrollValueHistory extended = historyRepository.saveAndFlush(successor);
            log.info("Employee {} value {} backdated to {} (history id {})",
                    employeeId, code, effectiveFrom, extended.getId());
            return extended;
        }

        if (covering != null) {
            if (!covering.getValidFrom().isBefore(effectiveFrom)) {
                // Closing it would produce valid_until < valid_from.
                throw new ConflictException(
                        "Novi period mora počinjati posle " + covering.getValidFrom() + ".");
            }
            // INCLUSIVE valid_until: the old period's last day is the day before the
            // new one starts, so the two touch without overlapping.
            covering.setValidUntil(effectiveFrom.minusDays(1));

            // saveAndFlush, NOT save. Hibernate orders INSERTs before UPDATEs within
            // a flush, and the identity generator forces the INSERT out immediately —
            // so a plain save() would push the new row into the database while the old
            // period is still open, and ex_epvh_no_overlap would reject a change that
            // is perfectly legal. The close has to reach the database first.
            historyRepository.saveAndFlush(covering);
        }

        // Open-ended unless something already starts later, in which case the new
        // period stops the day before it. Same rule for appending, filling a gap and
        // prepending — one rule rather than three branches to keep consistent.
        LocalDate validUntil = successor == null ? null : successor.getValidFrom().minusDays(1);

        EmployeePayrollValueHistory created = historyRepository.save(
                EmployeePayrollValueHistory.builder()
                        .employee(employee)
                        .definition(definition)
                        .valueType(definition.getValueType())
                        .numericValue(numericValue)
                        .booleanValue(booleanValue)
                        .validFrom(effectiveFrom)
                        .validUntil(validUntil)
                        .note(note)
                        .createdBy(changedBy)
                        .build());

        log.info("Employee {} value {} set to {} for [{} .. {}] (history id {})",
                employeeId, code, numericValue != null ? numericValue : booleanValue, effectiveFrom,
                validUntil == null ? "open" : validUntil, created.getId());

        return created;
    }

    /** Does this period already say exactly what is being written? */
    private static boolean holdsSameValue(EmployeePayrollValueHistory period,
                                          BigDecimal numericValue, Boolean booleanValue) {
        if (numericValue != null) {
            return period.getNumericValue() != null
                    && period.getNumericValue().compareTo(numericValue) == 0;
        }
        return java.util.Objects.equals(period.getBooleanValue(), booleanValue);
    }

    /**
     * Set the value in force from a date, correcting the period that already
     * starts on that date rather than refusing.
     *
     * <p>{@link #changeValue} is the deliberate "add a period" operation: a date
     * that is already taken is a mistake there, and it says so. This is the other
     * intent — "this is the rate from this date" — which an administrator can
     * express twice in the same month simply by fixing a typo. Making that second
     * save an error would send them to delete a history row to correct a number
     * they had just entered.
     *
     * <p>Correcting in place is also the honest record. Two periods starting the
     * same month, one of which was never true, would read as a mid-month raise.
     * The old value is not lost: employee_payroll_value_history is audited, so
     * the correction is recoverable from audit_logs.
     *
     * @return the affected period, or empty when the value in force on that date
     *         already equals {@code numericValue} and there is nothing to record.
     */
    @Transactional
    public Optional<EmployeePayrollValueHistory> setValue(Long employeeId,
                                                          String code,
                                                          BigDecimal numericValue,
                                                          LocalDate effectiveFrom,
                                                          String note,
                                                          Long changedBy) {
        requireEmployee(employeeId);
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Datum početka primene je obavezan.");
        }
        if (numericValue == null) {
            throw new IllegalArgumentException("Vrednost je obavezna.");
        }

        EmployeePayrollValueDefinition definition = definitionRepository.findByCode(code)
                .orElseThrow(() -> new EntityNotFoundException("Nepoznata vrsta vrednosti: " + code));

        EmployeePayrollValueHistory sameStart =
                historyRepository.lockPeriodsFor(employeeId, definition.getId()).stream()
                        .filter(p -> p.getValidFrom().equals(effectiveFrom))
                        .findFirst().orElse(null);

        if (sameStart != null) {
            if (sameStart.getNumericValue() != null
                    && sameStart.getNumericValue().compareTo(numericValue) == 0) {
                return Optional.empty();
            }
            BigDecimal previous = sameStart.getNumericValue();
            sameStart.setNumericValue(numericValue);
            if (note != null) {
                sameStart.setNote(note);
            }
            EmployeePayrollValueHistory corrected = historyRepository.saveAndFlush(sameStart);
            log.info("Employee {} value {} corrected from {} to {} for the period starting {} "
                            + "(history id {})",
                    employeeId, code, previous, numericValue, effectiveFrom, corrected.getId());
            return Optional.of(corrected);
        }

        // No period starts here. If the one covering this date already holds the
        // value, there is genuinely nothing to record — changeValue would reject
        // it, and that rejection is not an error the caller should have to catch.
        Optional<BigDecimal> inForce = numericValueOn(employeeId, code, effectiveFrom);
        if (inForce.isPresent() && inForce.get().compareTo(numericValue) == 0) {
            return Optional.empty();
        }

        return Optional.of(changeValue(employeeId, code, numericValue, effectiveFrom, note, changedBy));
    }

    private Employee requireEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));
    }
}
