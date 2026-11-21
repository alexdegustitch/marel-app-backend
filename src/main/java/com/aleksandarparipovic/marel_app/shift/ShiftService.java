package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.shift.dto.ShiftOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final EmployeeShiftBreakPeriodRepository breakPeriodRepository;

    @Transactional(readOnly = true)
    public List<ShiftOptionDto> getActiveShiftOptions() {
        // The default pauze ride along so a screen with no employee yet (the
        // create-employee form) can show what each shift's breaks are today.
        Map<Long, List<EmployeeShiftBreakPeriod>> breaks = breakPeriodRepository
                .findDefaultsInForce(LocalDate.now()).stream()
                .collect(Collectors.groupingBy(p -> p.getShift().getId()));
        return shiftRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByStartTimeAsc()
                .stream()
                .map(shift -> new ShiftOptionDto(
                        shift.getId(),
                        shift.getShiftCode(),
                        shift.getName(),
                        shift.getStartTime(),
                        shift.getEndTime(),
                        breaks.getOrDefault(shift.getId(), List.of()).stream()
                                .map(ShiftOptionDto.DefaultBreakOptionDto::from)
                                .toList()
                ))
                .toList();
    }
}
