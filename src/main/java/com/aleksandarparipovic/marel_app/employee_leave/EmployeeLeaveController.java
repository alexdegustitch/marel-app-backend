package com.aleksandarparipovic.marel_app.employee_leave;

import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeaveApplyResponse;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeavePeriodDto;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeavePreviewResponse;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeaveRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Leave entered for a whole od–do period, from the worker's calendar.
 *
 * <p>Preview first, apply the SAME body after: the two run one planner, so the
 * screen's table of days and the write cannot disagree. No method-level
 * permission — entering a leave day is entering a shift, and shift entry is
 * guarded at the route (WORK_RECORD_VIEW), exactly like the karton's.
 */
@RestController
@RequestMapping("/api/employee-leaves")
@RequiredArgsConstructor
public class EmployeeLeaveController {

    private final EmployeeLeaveService service;

    @PostMapping("/preview")
    public ResponseEntity<LeavePreviewResponse> preview(@Valid @RequestBody LeaveRequest request) {
        return ResponseEntity.ok(service.preview(request));
    }

    @PostMapping
    public ResponseEntity<LeaveApplyResponse> apply(@Valid @RequestBody LeaveRequest request) {
        return ResponseEntity.ok(service.apply(request));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<LeavePeriodDto>> periodsForMonth(
            @PathVariable Long employeeId,
            @RequestParam int year,
            @RequestParam int month
    ) {
        return ResponseEntity.ok(service.periodsForMonth(employeeId, year, month));
    }

    /** Withdraws the period RECORD; the shifts it created stand. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archivePeriod(@PathVariable Long id) {
        service.archivePeriod(id);
        return ResponseEntity.noContent().build();
    }
}
