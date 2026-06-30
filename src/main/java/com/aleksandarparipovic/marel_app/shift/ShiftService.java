package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.shift.dto.ShiftOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;

    @Transactional(readOnly = true)
    public List<ShiftOptionDto> getActiveShiftOptions() {
        return shiftRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByStartTimeAsc()
                .stream()
                .map(shift -> new ShiftOptionDto(
                        shift.getId(),
                        shift.getShiftCode(),
                        shift.getName(),
                        shift.getStartTime(),
                        shift.getEndTime()
                ))
                .toList();
    }
}

