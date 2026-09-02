package com.aleksandarparipovic.marel_app.absence_compensation;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.OvertimeBankDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.OvertimeDayDto;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecord;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What one month's overtime bank holds, and how much of it is still unspent.
 *
 * <p>One month, no carry-over: the caller names a month and nothing here reaches
 * past either end of it. August overtime cannot pay for a September absence.
 *
 * <p>Read by two callers with different needs — the karton wants the whole
 * picture (which day earned what, and what has already been spent), while
 * {@code ShiftAbsenceSync} only wants the one number. Both go through here so
 * "what is left in the bank" cannot come out differently depending on who asked.
 */
@Service
@RequiredArgsConstructor
public class OvertimeBankService {

    private final OvertimeRecordRepository overtimeRepository;
    private final AbsenceRecordRepository absenceRepository;
    private final AbsenceCompensationRepository compensationRepository;

    /** Earned minus spent: what is still there to buy a day back with. */
    @Transactional(readOnly = true)
    public int remainingMinutes(Long employeeId, YearMonth month) {
        return bankFor(employeeId, month).remainingMinutes();
    }

    @Transactional(readOnly = true)
    public OvertimeBankDto bankFor(Long employeeId, int year, int month) {
        return bankFor(employeeId, YearMonth.of(year, month));
    }

    @Transactional(readOnly = true)
    public OvertimeBankDto bankFor(Long employeeId, YearMonth period) {
        List<OvertimeRecord> days = overtimeRepository
                .findForEmployeeBetween(employeeId, period.atDay(1), period.atEndOfMonth());

        Map<Long, Integer> spentByDay = compensationRepository
                .findForAbsences(absenceRepository
                        .findActiveForEmployeeBetween(employeeId, period.atDay(1), period.atEndOfMonth())
                        .stream().map(AbsenceRecord::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        c -> c.getOvertimeRecord().getId(),
                        Collectors.summingInt(AbsenceCompensation::getCompensatedMinutes)));

        List<OvertimeDayDto> dayDtos = days.stream()
                .map(o -> new OvertimeDayDto(o.getWorkDate(), o.getOvertimeMinutes(),
                        spentByDay.getOrDefault(o.getId(), 0)))
                .toList();

        int earned = dayDtos.stream().mapToInt(OvertimeDayDto::overtimeMinutes).sum();
        int spent = dayDtos.stream().mapToInt(OvertimeDayDto::spentMinutes).sum();
        return new OvertimeBankDto(earned, spent, earned - spent, dayDtos);
    }
}
