package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftDto;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftInfo;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkShiftService {

    private final WorkShiftRepository repository;
    private final CurrentUserService currentUserService;

    public List<WorkShiftDto> findLastThreePerMonthForSupervisor(int year){
        Long userId = currentUserService.getCurrentUserId();
        return repository.findLastThreePerMonthForSupervisor(userId, year);
    }

    public Page<WorkShiftInfo> getWorkShiftsByYearAndMonth(
            Integer year,
            Integer month,
            String search,
            Pageable pageable
    ) {
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);

        return repository.findMonthlyShifts(start, end, search, pageable);
    }
}
