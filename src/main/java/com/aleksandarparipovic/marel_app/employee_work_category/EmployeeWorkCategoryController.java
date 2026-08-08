package com.aleksandarparipovic.marel_app.employee_work_category;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employees/{employeeId}")
@RequiredArgsConstructor
public class EmployeeWorkCategoryController {

    private final EmployeeWorkCategoryService service;

    /** Every spell of this employee's default work category, newest first. */
    @GetMapping("/work-category-history")
    public ResponseEntity<List<EmployeeWorkCategoryPeriodDto>> history(@PathVariable Long employeeId) {
        return ResponseEntity.ok(service.history(employeeId));
    }

    /**
     * Move the employee to a different default category from a date.
     *
     * <p>Appends a period and closes the previous one; it never rewrites what
     * they worked in before. Triggers no recalculation — this category reaches
     * no calculation.
     */
    @PostMapping("/work-category-history")
    public ResponseEntity<EmployeeWorkCategoryPeriodDto> change(
            @PathVariable Long employeeId,
            @Valid @RequestBody ChangeWorkCategoryRequest request
    ) {
        return ResponseEntity.ok(service.change(employeeId, request));
    }
}
