package com.aleksandarparipovic.marel_app.employee_calendar;

import com.aleksandarparipovic.marel_app.employee_calendar.dto.EmployeeCalendarDtos.EmployeeCalendarResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The worker's calendar page — one month in one answer. Route-guarded like the karton. */
@RestController
@RequestMapping("/api/employee-calendar")
@RequiredArgsConstructor
public class EmployeeCalendarController {

    private final EmployeeCalendarService service;

    @GetMapping("/{employeeId}")
    public ResponseEntity<EmployeeCalendarResponse> month(
            @PathVariable Long employeeId,
            @RequestParam int year,
            @RequestParam int month
    ) {
        return ResponseEntity.ok(service.month(employeeId, year, month));
    }
}
