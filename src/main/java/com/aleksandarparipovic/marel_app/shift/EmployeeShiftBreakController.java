package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftBreakRequest;
import com.aleksandarparipovic.marel_app.shift.dto.EmployeeShiftBreakPeriodDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * An employee's own pauza windows. Lives under /api/employees/** so the same
 * door that opens the worker's page (and their shift times beside it) opens
 * this.
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/shift-breaks")
@RequiredArgsConstructor
public class EmployeeShiftBreakController {

    private final EmployeeShiftBreakService service;

    /** Every spell of this employee's own break windows, newest first. */
    @GetMapping
    public ResponseEntity<List<EmployeeShiftBreakPeriodDto>> history(@PathVariable Long employeeId) {
        return ResponseEntity.ok(service.history(employeeId));
    }

    /**
     * State one break for the employee from a date — their own window, an
     * extra break, or (suppressed) that they skip a default one.
     */
    @PostMapping
    public ResponseEntity<EmployeeShiftBreakPeriodDto> change(
            @PathVariable Long employeeId,
            @Valid @RequestBody ChangeShiftBreakRequest request
    ) {
        return ResponseEntity.ok(service.change(employeeId, request));
    }

    /** Close the open spell of one break — the default applies again after endedOn. */
    @PostMapping("/{shiftId}/close")
    public ResponseEntity<Void> close(
            @PathVariable Long employeeId,
            @PathVariable Long shiftId,
            @RequestParam Integer breakNo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endedOn
    ) {
        service.close(employeeId, shiftId, breakNo, endedOn);
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
