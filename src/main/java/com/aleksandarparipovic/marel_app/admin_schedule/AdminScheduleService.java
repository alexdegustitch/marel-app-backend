package com.aleksandarparipovic.marel_app.admin_schedule;

import com.aleksandarparipovic.marel_app.admin_schedule.dto.AdminScheduleEntryDto;
import com.aleksandarparipovic.marel_app.admin_schedule.dto.CreateAdminScheduleRequest;
import com.aleksandarparipovic.marel_app.admin_schedule.dto.UpdateAdminScheduleRequest;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.shift.ShiftTimeResolver;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The administration's daily roster: who of the administrators works which
 * shift on which day. App users only — nothing here touches work_shifts,
 * kartoni or payroll.
 *
 * <p>The rules are V68's: the unit is the day, a person may hold several rows
 * in one day, and a set start/end pair narrows a row to a window inside the
 * shift. The only refusal besides plain validation is an exact duplicate —
 * the same person, day, shift and window twice would be the same statement
 * said twice, not a schedule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminScheduleService {

    /** The role whose holders this roster schedules (display name "Administrator"). */
    static final String SCHEDULED_ROLE = "supervisor";

    private final AdminScheduleRepository repository;
    private final UserRepository userRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftTimeResolver shiftTimeResolver;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<AdminScheduleEntryDto> listBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Datum „do“ ne može biti pre datuma „od“.");
        }
        return repository.findLiveBetween(from, to).stream()
                .map(AdminScheduleEntryDto::from)
                .toList();
    }

    @Transactional
    public List<AdminScheduleEntryDto> create(CreateAdminScheduleRequest request) {
        requireWindowPair(request.getStartTime(), request.getEndTime());

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new EntityNotFoundException("Korisnik ne postoji: " + request.getUserId()));
        if (user.getRole() == null || !SCHEDULED_ROLE.equalsIgnoreCase(user.getRole().getRoleName())) {
            throw new IllegalArgumentException("Na raspored administracije idu samo administratori.");
        }
        if (!Boolean.TRUE.equals(user.getActive()) || user.getArchivedAt() != null) {
            throw new ConflictException("Nalog " + user.getFullName() + " nije aktivan i ne može na raspored.");
        }

        Shift shift = requireActiveShift(request.getShiftId());

        List<LocalDate> dates = request.getDates().stream().distinct().sorted().toList();
        Long createdBy = currentUserService.getCurrentUserId();
        List<AdminScheduleEntry> created = new ArrayList<>(dates.size());

        for (LocalDate date : dates) {
            requireWindowOverlapsShift(shift, date, request.getStartTime(), request.getEndTime());
            refuseDuplicate(user.getId(), date, shift.getId(), request.getStartTime(), request.getEndTime(), null);
            created.add(AdminScheduleEntry.builder()
                    .workDate(date)
                    .user(user)
                    .shift(shift)
                    .startTime(request.getStartTime())
                    .endTime(request.getEndTime())
                    .note(trimmed(request.getNote()))
                    .createdBy(createdBy)
                    .build());
        }

        List<AdminScheduleEntry> saved = repository.saveAllAndFlush(created);
        log.info("Admin schedule: {} day(s) of shift {} assigned to user {} ({} – {})",
                saved.size(), shift.getShiftCode(), user.getId(), dates.get(0), dates.get(dates.size() - 1));
        return saved.stream().map(AdminScheduleEntryDto::from).toList();
    }

    @Transactional
    public AdminScheduleEntryDto update(Long id, UpdateAdminScheduleRequest request) {
        requireWindowPair(request.getStartTime(), request.getEndTime());

        AdminScheduleEntry entry = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stavka rasporeda ne postoji: " + id));
        if (entry.getArchivedAt() != null) {
            throw new ConflictException("Uklonjena stavka rasporeda se ne menja.");
        }

        Shift shift = entry.getShift().getId().equals(request.getShiftId())
                ? entry.getShift()
                : requireActiveShift(request.getShiftId());

        requireWindowOverlapsShift(shift, entry.getWorkDate(), request.getStartTime(), request.getEndTime());
        refuseDuplicate(entry.getUser().getId(), entry.getWorkDate(), shift.getId(),
                request.getStartTime(), request.getEndTime(), entry.getId());

        entry.setShift(shift);
        entry.setStartTime(request.getStartTime());
        entry.setEndTime(request.getEndTime());
        entry.setNote(trimmed(request.getNote()));
        return AdminScheduleEntryDto.from(repository.saveAndFlush(entry));
    }

    /** Removal archives — the audit trail keeps what the roster used to say. */
    @Transactional
    public void archive(Long id) {
        AdminScheduleEntry entry = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stavka rasporeda ne postoji: " + id));
        if (entry.getArchivedAt() != null) {
            return;
        }
        entry.setArchivedAt(OffsetDateTime.now());
        entry.setArchivedBy(currentUserService.getCurrentUserId());
        repository.save(entry);
    }

    private Shift requireActiveShift(Long shiftId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + shiftId));
        if (!Boolean.TRUE.equals(shift.getIsActive()) || shift.getArchivedAt() != null) {
            throw new ConflictException("Smena " + shift.getShiftCode() + " nije aktivna.");
        }
        return shift;
    }

    private void refuseDuplicate(Long userId, LocalDate date, Long shiftId,
                                 LocalTime startTime, LocalTime endTime, Long excludeId) {
        boolean clash = repository.findLiveFor(userId, date, shiftId).stream()
                .filter(e -> excludeId == null || !e.getId().equals(excludeId))
                .anyMatch(e -> Objects.equals(e.getStartTime(), startTime)
                        && Objects.equals(e.getEndTime(), endTime));
        if (clash) {
            throw new ConflictException("Ta dodela za " + date + " već postoji (ista smena i isto vreme).");
        }
    }

    /**
     * A set window must SHARE at least a minute with the shift as it runs on
     * that date — the versioned default hours (V50), falling back to the
     * shifts row, through the same resolver every other reader of shift hours
     * uses. Sticking out is legal (the owner's rule: part of the interval in
     * the shift is enough); only an interval ENTIRELY outside the shift is
     * refused, because that time belongs to some other shift. Overnight is
     * counted properly: against shift III (22:00–06:00), 21:00–23:00 overlaps
     * and 10:00–12:00 does not.
     */
    private void requireWindowOverlapsShift(Shift shift, LocalDate date,
                                            LocalTime startTime, LocalTime endTime) {
        if (startTime == null) {
            return;
        }
        ShiftTimeResolver.ResolvedShiftTimes times = shiftTimeResolver.resolve(null, shift, date);
        if (windowOutsideShift(times.startTime(), times.endTime(), startTime, endTime)) {
            throw new IllegalArgumentException(
                    "Vreme od–do je potpuno van smene " + shift.getShiftCode()
                            + " (" + times.startTime() + "–" + times.endTime() + ") za " + date
                            + " — izaberite smenu kojoj to vreme pripada.");
        }
    }

    /**
     * Whether [windowStart, windowEnd) and [shiftStart, shiftEnd) share no
     * minute at all, on the clock circle so an overnight shift and an
     * overnight window both read correctly. Package-private for the unit test.
     */
    static boolean windowOutsideShift(LocalTime shiftStart, LocalTime shiftEnd,
                                      LocalTime windowStart, LocalTime windowEnd) {
        int shiftLength = minutesForward(shiftStart, shiftEnd);
        int relStart = minutesForward(shiftStart, windowStart);
        int windowLength = minutesForward(windowStart, windowEnd);
        // The window overlaps the shift when it STARTS inside it
        // (relStart < shiftLength), or when it wraps the clock past the
        // shift's start (relStart + windowLength > 1440) and so re-enters it.
        return relStart >= shiftLength && relStart + windowLength <= 1440;
    }

    /** Minutes from {@code from} forward to {@code to}, wrapping midnight. */
    private static int minutesForward(LocalTime from, LocalTime to) {
        int diff = (to.toSecondOfDay() - from.toSecondOfDay()) / 60;
        return ((diff % 1440) + 1440) % 1440;
    }

    private static void requireWindowPair(LocalTime startTime, LocalTime endTime) {
        if ((startTime == null) != (endTime == null)) {
            throw new IllegalArgumentException("Vreme „od“ i „do“ idu zajedno — unesite oba ili nijedno.");
        }
        // end < start is legal (a window across midnight, shift III); equal is not.
        if (startTime != null && startTime.equals(endTime)) {
            throw new IllegalArgumentException("Vreme „od“ i „do“ ne mogu biti ista.");
        }
    }

    private static String trimmed(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }
}
