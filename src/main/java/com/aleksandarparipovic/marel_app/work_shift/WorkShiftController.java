package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.work_shift.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    @GetMapping("/info/{id}")
    public ResponseEntity<WorkShiftInfoDto> getShiftInfo(@PathVariable Long id){
        return ResponseEntity.ok(service.getWorkShiftInfo(id));
    }

    /** GET /api/work-shifts/years — years that actually hold kartoni, newest first. */
    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getYearsWithShifts(){
        return ResponseEntity.ok(service.findYearsWithShifts());
    }

    @GetMapping("/year/{year}")
    public ResponseEntity<List<WorkShiftActivityDto>> getLastWorkShifts(@PathVariable Integer year){
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

    @PostMapping
    public ResponseEntity<WorkShiftBasicInfoDto> createShift(
            @Valid @RequestBody WorkShiftCreateRequest request
    ) {
        return ResponseEntity.ok(service.createShift(request));
    }

    @PutMapping("/{workShiftId}")
    public ResponseEntity<WorkShiftBasicInfoDto> updateShift(
            @PathVariable Long workShiftId,
            @Valid @RequestBody UpdateWorkShiftRequest request
    ) {
        return ResponseEntity.ok(service.updateShift(workShiftId, request));
    }

    @GetMapping("/ids/{id}")
    public ResponseEntity<List<Long>> getShiftsForEmployeeRecord(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ){
        return ResponseEntity.ok(service.getShiftsForEmployeeRecord(id, fromDate, toDate));
    }




    /**
     * Withdraw a whole shift.
     *
     * <p>Its own permission: taking a shift back removes a day of work from what
     * somebody is paid, which is a heavier decision than correcting the hours on
     * it. Refused while the month's payroll is locked.
     */
    @PreAuthorize("@perm.has('WORK_SHIFT_ARCHIVE')")
    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archiveShift(@PathVariable Long id,
                                             @RequestParam(required = false) String reason) {
        service.archive(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@perm.has('WORK_SHIFT_ARCHIVE')")
    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restoreShift(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Delete a shift that never held anything — no work logs, no absences.
     *
     * <p>Anything else is archived. The database agrees: the child tables are
     * ON DELETE RESTRICT, so this cannot quietly become a way to erase work.
     */
    @PreAuthorize("@perm.has('WORK_SHIFT_ARCHIVE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmptyShift(@PathVariable Long id) {
        service.deleteEmpty(id);
        return ResponseEntity.noContent().build();
    }
}
