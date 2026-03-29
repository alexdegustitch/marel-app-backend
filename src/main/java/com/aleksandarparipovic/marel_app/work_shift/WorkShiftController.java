package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.work_shift.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/work-shifts")
@RequiredArgsConstructor
public class WorkShiftController {

    private final WorkShiftService service;

    @GetMapping("/{id}")
    public ResponseEntity<WorkShiftBasicInfoDto> getShiftById(@PathVariable Long id){
        return ResponseEntity.ok(service.getWorkShiftById(id));
    }

    @GetMapping("/year/{year}")
    public ResponseEntity<List<WorkShiftDto>> getLastWorkShifts(@PathVariable Integer year){
        return ResponseEntity.ok(service.findLastThreePerMonthForSupervisor(year));
    }

    @GetMapping
    public ResponseEntity<Page<WorkShiftInfo>> getWorkShiftsByYearAndMonth(
            @RequestParam Integer year,
            @RequestParam Integer month,
            @RequestParam(required = false) String globalSearch,
            Pageable pageable
    ) {
        return ResponseEntity.ok(service.getWorkShiftsByYearAndMonth(year, month, globalSearch, pageable));
    }

    @GetMapping("/employee-shifts")
    public ResponseEntity<List<WorkShiftWithLogsDto>> getShiftsForEmployee(
            @RequestParam Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month
    ){
        return ResponseEntity.ok(service.getShiftsForEmployee(employeeId, year, month));
    }

    @GetMapping("/employee-shifts-preview")
    public ResponseEntity<List<WorkShiftWithLogsPreviewDto>> getShiftsForEmployeePreview(
            @RequestParam Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month
    ){
        return ResponseEntity.ok(service.getShiftsPreviewForEmployee(employeeId, year, month));
    }



}
