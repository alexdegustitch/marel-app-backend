package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.shift.dto.EmployeeShiftTimePeriodDto;
import com.aleksandarparipovic.marel_app.shift.dto.ShiftAdminDto;
import com.aleksandarparipovic.marel_app.shift.dto.UpsertShiftRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The šifarnik's administration of shifts.
 *
 * <p>The shifts row keeps only the CURRENT default hours — the mirror every
 * picker reads. Their HISTORY lives in employee_shift_time_periods as the
 * employee_id-NULL rows: changing the default closes the open spell the day
 * before effectiveFrom and opens a new one, so "what should have been worked
 * on that date" stays answerable. The same start date is a CORRECTION.
 *
 * <p>Nothing here rewrites a work_shifts row already written — those are the
 * material truth payroll reads; a moved default reaches only shifts created
 * (and boundaries recalculated) after it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftAdminService {

    /** The night-bonus rule keys on this code; see DailyRecalcService.isNightShift. */
    private static final String NIGHT_SHIFT_CODE = "III";

    private final ShiftRepository shiftRepository;
    private final EmployeeShiftTimePeriodRepository periodRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<ShiftAdminDto> listAll() {
        Map<Long, LocalDate> defaultSince = periodRepository.findAll().stream()
                .filter(p -> p.getEmployee() == null && p.getArchivedAt() == null && p.getValidTo() == null)
                .collect(Collectors.toMap(p -> p.getShift().getId(), EmployeeShiftTimePeriod::getValidFrom,
                        (a, b) -> a.isAfter(b) ? a : b));
        return shiftRepository.findAllByOrderByStartTimeAsc().stream()
                .map(s -> ShiftAdminDto.from(s, defaultSince.get(s.getId())))
                .toList();
    }

    /** The default-hours history of one shift, newest first. */
    @Transactional(readOnly = true)
    public List<EmployeeShiftTimePeriodDto> defaultHistory(Long shiftId) {
        return periodRepository.findDefaultHistoryFor(shiftId).stream()
                .map(EmployeeShiftTimePeriodDto::from)
                .toList();
    }

    @Transactional
    public ShiftAdminDto create(UpsertShiftRequest request) {
        validateTimes(request);
        String code = request.getShiftCode().trim();
        if (shiftRepository.existsByShiftCodeIgnoreCase(code)) {
            throw new ConflictException("Smena sa oznakom \"" + code + "\" već postoji.");
        }

        Shift shift = shiftRepository.saveAndFlush(Shift.builder()
                .shiftCode(code)
                .name(request.getName())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .isActive(request.getIsActive() == null || request.getIsActive())
                .build());

        // The default spell opens far in the past, exactly as the seed did for
        // the original three: the resolver must never fall back to the mutable
        // shifts row for a date this table could answer.
        EmployeeShiftTimePeriod period = periodRepository.saveAndFlush(EmployeeShiftTimePeriod.builder()
                .shift(shift)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(LocalDate.of(1970, 1, 1))
                .note("Podrazumevano vreme smene, otvoreno sa smenom.")
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.info("Shift {} created ({}–{})", code, request.getStartTime(), request.getEndTime());
        return ShiftAdminDto.from(shift, period.getValidFrom());
    }

    @Transactional
    public ShiftAdminDto update(Long id, UpsertShiftRequest request) {
        validateTimes(request);
        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + id));

        String code = request.getShiftCode().trim();
        if (!shift.getShiftCode().equalsIgnoreCase(code)) {
            // The night bonus keys on the third shift BY CODE; renaming it away
            // would silently stop every night remap. The name is free to change.
            if (NIGHT_SHIFT_CODE.equalsIgnoreCase(shift.getShiftCode())) {
                throw new ConflictException(
                        "Oznaka \"III\" se ne može menjati — na nju je vezan obračun noćnog dodatka."
                                + " Naziv smene može slobodno da se promeni.");
            }
            if (shiftRepository.existsByShiftCodeIgnoreCase(code)) {
                throw new ConflictException("Smena sa oznakom \"" + code + "\" već postoji.");
            }
            shift.setShiftCode(code);
        }
        shift.setName(request.getName());
        if (request.getIsActive() != null) {
            shift.setIsActive(request.getIsActive());
        }

        boolean timesChanged = !shift.getStartTime().equals(request.getStartTime())
                || !shift.getEndTime().equals(request.getEndTime());
        LocalDate openedFrom = null;
        if (timesChanged) {
            openedFrom = versionDefaultPeriod(shift, request);
            shift.setStartTime(request.getStartTime());
            shift.setEndTime(request.getEndTime());
        }

        Shift saved = shiftRepository.saveAndFlush(shift);
        LocalDate since = openedFrom != null ? openedFrom
                : periodRepository.findOpenDefaultFor(id).map(EmployeeShiftTimePeriod::getValidFrom).orElse(null);
        return ShiftAdminDto.from(saved, since);
    }

    /**
     * Close the open default spell and open the new one from effectiveFrom.
     * The same start date is a CORRECTION of the open spell — the default
     * never really ran with the mistyped hours, so no second version opens.
     */
    private LocalDate versionDefaultPeriod(Shift shift, UpsertShiftRequest request) {
        LocalDate from = request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now();
        EmployeeShiftTimePeriod open = periodRepository.findOpenDefaultFor(shift.getId()).orElse(null);

        if (open == null) {
            // A shift with no default spell on record (data older than the
            // period table). Its history is reconstructed from the row's OLD
            // times before the change is applied — otherwise every past date
            // would fall back to the row and drift with the new times.
            open = periodRepository.saveAndFlush(EmployeeShiftTimePeriod.builder()
                    .shift(shift)
                    .startTime(shift.getStartTime())
                    .endTime(shift.getEndTime())
                    .validFrom(LocalDate.of(1970, 1, 1))
                    .note("Podrazumevano vreme smene, rekonstruisano pri izmeni.")
                    .createdBy(currentUserService.getCurrentUserId())
                    .build());
        }

        if (open != null && open.getValidFrom().equals(from)) {
            open.setStartTime(request.getStartTime());
            open.setEndTime(request.getEndTime());
            periodRepository.saveAndFlush(open);
            return open.getValidFrom();
        }
        if (open != null) {
            if (!from.isAfter(open.getValidFrom())) {
                throw new IllegalArgumentException(
                        "Novo podrazumevano vreme mora da počne posle početka trenutnog ("
                                + open.getValidFrom() + ").");
            }
            open.setValidTo(from.minusDays(1));
            periodRepository.saveAndFlush(open);
        }
        periodRepository.saveAndFlush(EmployeeShiftTimePeriod.builder()
                .shift(shift)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(from)
                .note("Promena podrazumevanog vremena smene.")
                .createdBy(currentUserService.getCurrentUserId())
                .build());
        log.info("Shift {} default hours move to {}–{} from {}",
                shift.getShiftCode(), request.getStartTime(), request.getEndTime(), from);
        return from;
    }

    private void validateTimes(UpsertShiftRequest request) {
        if (request.getStartTime().equals(request.getEndTime())) {
            throw new IllegalArgumentException("Početak i kraj smene ne mogu biti isti.");
        }
    }
}
