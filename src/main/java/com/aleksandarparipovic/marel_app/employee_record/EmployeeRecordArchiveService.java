package com.aleksandarparipovic.marel_app.employee_record;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.ArchivePreview;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.ArchiveSummary;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.MonthArchivePreview;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.YearMonth;
import java.util.List;

/**
 * Taking a karton back out of a month, and putting it back.
 *
 * <p><b>Why its own service.</b> {@link WorkShiftService} already depends on
 * {@link EmployeeRecordService} — every shift asks it for the karton to hang
 * off — so the record service cannot call back into the shift service without
 * closing a circle. Withdrawing a karton has to withdraw the shifts in it, so
 * it lives here instead, beside both rather than inside either. The same reason
 * the leave materialisation is composed in the controller.
 *
 * <p><b>ARCHIVED, NOT DELETED.</b> A karton is one worker's month, and the
 * shifts under it are what somebody was paid for. Withdrawing it stamps
 * {@code archived_at} / {@code archived_by} / {@code archive_reason} (V73),
 * flips {@code is_active} to false in step so every query already filtering on
 * it keeps working, and cascades into the shifts through
 * {@link WorkShiftService#archive} — which drops each day's report and requeues
 * the month, so the totals actually fall instead of lingering as a ghost.
 * Nothing is destroyed: the work logs, absences and audit trail stay exactly
 * where they were.
 *
 * <p>Deleting outright is reserved for a karton that never held anything — no
 * shifts at all, live or withdrawn, no monthly report, no payroll item. The
 * database agrees: {@code work_shifts} and {@code monthly_reports} reference
 * {@code employee_records} without ON DELETE CASCADE, so this cannot quietly
 * become a way to erase a month.
 *
 * <p><b>The month is the real guard.</b> A payroll that has been APPROVED, SENT
 * or LOCKED refuses the withdrawal outright, exactly as it refuses a single
 * shift's. Accepting it would leave a record of what was paid standing beside a
 * karton that no longer supports it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeRecordArchiveService {

    private final EmployeeRecordRepository recordRepository;
    private final WorkShiftRepository workShiftRepository;
    private final WorkShiftService workShiftService;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final CurrentUserService currentUserService;
    private final EntityManager entityManager;

    // ── Reading before deciding ──────────────────────────────────────────────

    /**
     * What withdrawing this karton would touch.
     *
     * <p>Counted rather than estimated, because the screen states it back to the
     * person before asking them to confirm. {@code blockedReason} is filled in
     * here too, so a karton that cannot be withdrawn says why on the dialog
     * instead of only on the failed attempt.
     */
    @Transactional(readOnly = true)
    public ArchivePreview preview(Long id) {
        EmployeeRecord record = require(id);

        long liveShifts = count("""
                SELECT count(*) FROM work_shifts
                WHERE employee_record_id = :id AND archived_at IS NULL
                """, id);
        long archivedShifts = count("""
                SELECT count(*) FROM work_shifts
                WHERE employee_record_id = :id AND archived_at IS NOT NULL
                """, id);
        long workLogs = count("""
                SELECT count(*) FROM work_logs wl
                JOIN work_shifts ws ON ws.id = wl.work_shift_id
                WHERE ws.employee_record_id = :id
                """, id);
        long absences = count("""
                SELECT count(*) FROM absence_records ar
                JOIN work_shifts ws ON ws.id = ar.work_shift_id
                WHERE ws.employee_record_id = :id
                """, id);
        long monthlyReports = count("""
                SELECT count(*) FROM monthly_reports WHERE employee_record_id = :id
                """, id);
        long payrollItems = payrollItemsFor(record);

        boolean deletable = liveShifts == 0 && archivedShifts == 0
                && monthlyReports == 0 && payrollItems == 0;

        return new ArchivePreview(
                liveShifts,
                workLogs,
                absences,
                monthlyReports > 0,
                archivedShifts,
                deletable,
                liveShifts > 0,
                blockedReason(record));
    }

    /** The same answer for a whole month, for the bulk dialog. */
    @Transactional(readOnly = true)
    public MonthArchivePreview previewMonth(int year, int month) {
        List<EmployeeRecord> live = liveKartoniOf(year, month);
        long shifts = 0;
        long blocked = 0;
        for (EmployeeRecord record : live) {
            shifts += count("""
                    SELECT count(*) FROM work_shifts
                    WHERE employee_record_id = :id AND archived_at IS NULL
                    """, record.getId());
            if (payrollItemsFor(record) > 0) {
                blocked++;
            }
        }
        LocalDate monthStart = YearMonth.of(year, month).atDay(1);
        long archived = ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM employee_records
                WHERE start_date = :monthStart AND archived_at IS NOT NULL
                """)
                .setParameter("monthStart", monthStart)
                .getSingleResult()).longValue();

        return new MonthArchivePreview(live.size(), shifts, blocked, archived);
    }

    // ── Withdrawing ──────────────────────────────────────────────────────────

    /**
     * Withdraw one karton and the live shifts in it.
     *
     * <p>The karton is stamped FIRST and the shifts after it, deliberately:
     * {@link #restore} brings back exactly the shifts archived at or after the
     * karton's own {@code archived_at}, so a shift somebody withdrew earlier, as
     * its own decision, stays withdrawn when the karton comes back.
     */
    @Transactional
    public ArchiveSummary archive(Long id, String reason) {
        EmployeeRecord record = require(id);

        if (record.getArchivedAt() != null) {
            throw new ConflictException("Karton je već arhiviran.");
        }
        String blocked = blockedReason(record);
        if (blocked != null) {
            throw new ConflictException(blocked);
        }

        /*
         * TRUNCATED TO MICROSECONDS, which is what restore depends on.
         *
         * The column is timestamp(6), so Postgres truncates whatever it is
         * given. An untruncated Java stamp would be stored BELOW its in-memory
         * value, and restore compares the shifts' stored archived_at against
         * this one — so a shift withdrawn in the same microsecond as the karton
         * would read as older than it and be left behind. Truncating here makes
         * the value Java holds and the value Postgres holds the same number.
         */
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        record.setArchivedAt(now);
        record.setArchivedBy(currentUserService.getCurrentUserId());
        record.setArchiveReason(reason == null || reason.isBlank() ? null : reason.trim());
        // Kept in step so every query already filtering on it keeps working.
        record.setActive(false);
        recordRepository.saveAndFlush(record);

        // Through the shift service, so a cascade behaves exactly like that many
        // single withdrawals: daily report dropped, month requeued, reason noted.
        List<Long> shiftIds = workShiftRepository.getShiftsForEmployeeRecord(id);
        for (Long shiftId : shiftIds) {
            workShiftService.archive(shiftId, reason);
        }

        // The payroll items learn their inputs moved. Closed months were refused
        // above, so nothing locked is marked.
        YearMonth period = YearMonth.from(record.getStartDate());
        payrollRunItemRepository.markNeedsRecalculationByEmployeeAndMonth(
                record.getEmployee().getId(), period.getYear(), period.getMonthValue());

        log.info("Karton {} ({} {}) archived with {} shifts by user {} ({})",
                id, record.getEmployee().getId(), period, shiftIds.size(),
                currentUserService.getCurrentUserId(), reason != null ? reason : "bez razloga");

        return new ArchiveSummary(1, shiftIds.size(), record.getStartDate());
    }

    /**
     * Withdraw every live karton of one month.
     *
     * <p>The whole month or none of it: if any karton's payroll is closed the
     * request is refused up front rather than archiving half a month and
     * stopping — half-done is the one outcome nobody asked for. The password was
     * already confirmed at the controller.
     */
    @Transactional
    public ArchiveSummary archiveMonth(int year, int month, String reason) {
        List<EmployeeRecord> live = liveKartoniOf(year, month);
        if (live.isEmpty()) {
            throw new ConflictException("Za izabrani mesec nema kartona za arhiviranje.");
        }

        for (EmployeeRecord record : live) {
            String blocked = blockedReason(record);
            if (blocked != null) {
                throw new ConflictException(
                        "Obračun za " + month + "/" + year + " je predat ili zaključan za najmanje"
                                + " jednog radnika, pa se kartoni tog meseca ne mogu arhivirati."
                                + " Vratite obračun na doradu pa pokušajte ponovo.");
            }
        }

        long shifts = 0;
        for (EmployeeRecord record : live) {
            shifts += archive(record.getId(), reason).shifts();
        }

        log.info("Archived {} kartoni ({} shifts) of {}/{} by user {}",
                live.size(), shifts, month, year, currentUserService.getCurrentUserId());
        return new ArchiveSummary(live.size(), shifts, YearMonth.of(year, month).atDay(1));
    }

    // ── Putting it back ──────────────────────────────────────────────────────

    /**
     * Put a withdrawn karton back, with the shifts that went down with it.
     *
     * <p>"With it" is exact: the shifts whose {@code archived_at} is at or after
     * the karton's. A shift withdrawn before the karton was a separate decision
     * and stays withdrawn — restoring it is its own action, on the shift.
     */
    @Transactional
    public ArchiveSummary restore(Long id) {
        EmployeeRecord record = require(id);

        if (record.getArchivedAt() == null) {
            return new ArchiveSummary(0, 0, record.getStartDate());
        }
        String blocked = blockedReason(record);
        if (blocked != null) {
            throw new ConflictException(blocked);
        }

        OffsetDateTime archivedAt = record.getArchivedAt();
        record.setArchivedAt(null);
        record.setArchivedBy(null);
        record.setArchiveReason(null);
        record.setActive(true);
        recordRepository.saveAndFlush(record);

        List<Long> shiftIds = workShiftRepository.findShiftsArchivedWithRecord(id, archivedAt);
        for (Long shiftId : shiftIds) {
            workShiftService.restore(shiftId);
        }

        YearMonth period = YearMonth.from(record.getStartDate());
        payrollRunItemRepository.markNeedsRecalculationByEmployeeAndMonth(
                record.getEmployee().getId(), period.getYear(), period.getMonthValue());

        log.info("Karton {} restored with {} shifts by user {}",
                id, shiftIds.size(), currentUserService.getCurrentUserId());
        return new ArchiveSummary(1, shiftIds.size(), record.getStartDate());
    }

    // ── Deleting the one case where nothing is lost ───────────────────────────

    /**
     * Delete a karton that never held anything.
     *
     * <p>No shifts at all — withdrawn ones count, because a withdrawal is itself
     * a record of something that happened — no monthly report, no payroll item.
     * Anything else is archived instead.
     *
     * <p>Its {@code employee_record_updates} rows go with it. They are a trail
     * of who last had the karton OPEN on screen, not of work: derived from
     * looking, and meaningless once there is nothing to look at.
     */
    @Transactional
    public void deleteEmpty(Long id) {
        EmployeeRecord record = require(id);
        ArchivePreview preview = preview(id);

        if (!preview.deletable()) {
            throw new ConflictException(
                    "Karton ima unete smene ili obračunske podatke i ne može se obrisati."
                            + " Arhivirajte ga — unosi ostaju zabeleženi.");
        }
        String blocked = blockedReason(record);
        if (blocked != null) {
            throw new ConflictException(blocked);
        }

        entityManager.createNativeQuery(
                        "DELETE FROM employee_record_updates WHERE employee_record_id = :id")
                .setParameter("id", id)
                .executeUpdate();
        recordRepository.delete(record);

        log.info("Empty karton {} deleted by user {}", id, currentUserService.getCurrentUserId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private EmployeeRecord require(Long id) {
        return recordRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Karton ne postoji: " + id));
    }

    private List<EmployeeRecord> liveKartoniOf(int year, int month) {
        LocalDate monthStart = YearMonth.of(year, month).atDay(1);
        return recordRepository.findAllByStartDateAndArchivedAtIsNull(monthStart);
    }

    /**
     * Why the month refuses to be changed, or null when it does not.
     *
     * <p>Covers APPROVED, SENT and LOCKED alike — the same set a single shift's
     * withdrawal refuses on. Locked is a record of what was paid; approved or
     * sent means the floor said the month was finished and payroll started from
     * it, so moving the totals then would change somebody's pay with nothing on
     * screen to explain it.
     */
    private String blockedReason(EmployeeRecord record) {
        if (record.getStartDate() == null || record.getEmployee() == null) {
            return null;
        }
        if (payrollItemsFor(record) > 0) {
            YearMonth period = YearMonth.from(record.getStartDate());
            return "Obračun za " + period.getMonthValue() + "/" + period.getYear()
                    + " je predat ili zaključan, pa se karton ne može menjati."
                    + " Vratite ga na doradu pa pokušajte ponovo.";
        }
        return null;
    }

    private long payrollItemsFor(EmployeeRecord record) {
        YearMonth period = YearMonth.from(record.getStartDate());
        return payrollRunItemRepository.countClosedForEmployeeAndMonth(
                record.getEmployee().getId(), period.getYear(), period.getMonthValue());
    }

    private long count(String sql, Long id) {
        Number value = (Number) entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .getSingleResult();
        return value != null ? value.longValue() : 0L;
    }
}
