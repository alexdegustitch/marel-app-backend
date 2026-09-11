package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftTimeRequest;
import com.aleksandarparipovic.marel_app.shift.dto.EffectiveShiftTimeDto;
import com.aleksandarparipovic.marel_app.shift.dto.EmployeeShiftTimePeriodDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * An employee's own shift hours. Lives under /api/employees/** so the same
 * door (EMPLOYEE_VIEW) that opens the worker's page opens this, exactly like
 * the work-category history beside it.
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/shift-times")
@RequiredArgsConstructor
public class EmployeeShiftTimeController {

    private final EmployeeShiftTimeService service;

    /** Every spell of this employee's own shift hours, newest first. */
    @GetMapping
    public ResponseEntity<List<EmployeeShiftTimePeriodDto>> history(@PathVariable Long employeeId) {
        return ResponseEntity.ok(service.history(employeeId));
    }

    /** When each active shift runs for this employee on a date (default: today). */
    @GetMapping("/effective")
    public ResponseEntity<List<EffectiveShiftTimeDto>> effective(
            @PathVariable Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(service.effective(employeeId, date != null ? date : LocalDate.now()));
    }

    /** Give the employee their own hours for a shift, from a date. */
    @PostMapping
    public ResponseEntity<EmployeeShiftTimePeriodDto> change(
            @PathVariable Long employeeId,
            @Valid @RequestBody ChangeShiftTimeRequest request
    ) {
        return ResponseEntity.ok(service.change(employeeId, request));
    }

    /** Close the open spell for a shift — the default applies again after endedOn. */
    @PostMapping("/{shiftId}/close")
    public ResponseEntity<Void> close(
            @PathVariable Long employeeId,
            @PathVariable Long shiftId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endedOn
    ) {
        service.close(employeeId, shiftId, endedOn);
        return ResponseEntity.noContent().build();
    }

    /** Withdraw a spell that should never have existed. */
    @DeleteMapping("/{periodId}")
    public ResponseEntity<Void> archive(
            @PathVariable Long employeeId,
            @PathVariable Long periodId
    ) {
        service.archive(employeeId, periodId);
        return ResponseEntity.noContent().build();
    }
}
