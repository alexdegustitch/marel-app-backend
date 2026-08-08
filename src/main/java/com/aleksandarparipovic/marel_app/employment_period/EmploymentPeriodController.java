package com.aleksandarparipovic.marel_app.employment_period;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employees/{employeeId}")
@RequiredArgsConstructor
public class EmploymentPeriodController {

    private final EmployeeEmploymentPeriodRepository repository;

    /**
     * Every spell this employee has worked here, newest first.
     *
     * <p>READ ONLY on purpose. A spell starts when the employee is created and
     * ends when they are ARCHIVED with a date; probation length is edited on the
     * employee itself. There is no path here that writes, because every way of
     * changing employment already has its own one with its own consequences.
     */
    @GetMapping("/employment-periods")
    public ResponseEntity<List<EmploymentPeriodDto>> history(@PathVariable Long employeeId) {
        return ResponseEntity.ok(repository.findLatest(employeeId).stream()
                .map(EmploymentPeriodDto::from)
                .toList());
    }
}
