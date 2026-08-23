package com.aleksandarparipovic.marel_app.employee_record;


import com.aleksandarparipovic.marel_app.employee_record.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/employee-records")
public class EmployeeRecordController {

    private final EmployeeRecordService service;

    @GetMapping("/last-activity")
    public ResponseEntity<List<EmployeeRecordDto>> getLastWorkShifts(@RequestParam Integer year,
                                                                     @RequestParam Integer month){
        return ResponseEntity.ok(service.findLastThreePerMonthForSupervisor(year, month));
    }

    @GetMapping
    public ResponseEntity<Page<EmployeeRecordInfo>> getEmployeeRecordsByYearAndMonth(
            @RequestParam Integer year,
            @RequestParam Integer month,
            @RequestParam(required = false) String globalSearch,
            Pageable pageable
    ) {
        return ResponseEntity.ok(service.getEmployeeRecordsByYearAndMonth(year, month, globalSearch, pageable));
    }

    @GetMapping("/exists")
    public ResponseEntity<Boolean> existsForEmployeeAndMonth(
            @RequestParam Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ResponseEntity.ok(service.existsForEmployeeAndMonth(employeeId, year, month));
    }

    /**
     * The karton for one employee and month, addressed the way a calendar knows
     * them. 204 when there is none, so the caller can offer the link or not
     * without treating "no karton yet" as a failure.
     */
    @GetMapping("/by-month")
    public ResponseEntity<EmployeeRecordRefDto> getByEmployeeAndMonth(
            @RequestParam Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return service.findRecordIdForEmployeeAndMonth(employeeId, year, month)
                .map(id -> ResponseEntity.ok(new EmployeeRecordRefDto(id, employeeId, year, month)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeRecordEmployeeInfo> getByEmployeeRecordId(@PathVariable Long id) {
        return ResponseEntity.ok(service.getByEmployeeRecordId(id));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<RecentEmployeeRecordDto>> getRecentByEmployeeId(
            @RequestParam Long employeeId,
            @RequestParam(defaultValue = "3") int size) {
        return ResponseEntity.ok(service.getRecentByEmployeeId(employeeId, size));
    }
    
    @PostMapping("/create-records")
    public ResponseEntity<EmployeeRecordCreateResponse> createEmployeeRecordsForMonth(
            @Valid @RequestBody EmployeeRecordCreateRequest request
    ) {
        return ResponseEntity.ok(service.createEmployeeRecordsForMonth(request.getYear(), request.getMonth()));
    }
}
