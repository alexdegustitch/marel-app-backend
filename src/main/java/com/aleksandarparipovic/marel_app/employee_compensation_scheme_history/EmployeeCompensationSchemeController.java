package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history;

import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.dto.ChangeCompensationSchemeRequest;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.dto.EmployeeCompensationSchemeHistoryDto;
import com.aleksandarparipovic.marel_app.work_category_resolution.AllowedWorkCodeCategoryService;
import com.aleksandarparipovic.marel_app.work_category_resolution.dto.AllowedWorkCodeCategoryDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Employee-scoped compensation-scheme endpoints, mounted under the existing
 * {@code /api/employees} namespace rather than a parallel one.
 *
 * <p>Kept thin: it validates and delegates. The availability and coefficient
 * rules live in the domain services.
 */
@RestController
@RequestMapping("/api/employees/{employeeId}")
@RequiredArgsConstructor
public class EmployeeCompensationSchemeController {

    private final EmployeeCompensationSchemeService schemeService;
    private final AllowedWorkCodeCategoryService allowedCategoryService;

    /**
     * The categories this employee may select on {@code date}.
     *
     * <p>Depends on both the employee AND the date: a scheme transition means the
     * same employee has a different list on either side of it, which is why the
     * work-entry form reloads on a change to either.
     */
    @GetMapping("/allowed-work-code-categories")
    public ResponseEntity<List<AllowedWorkCodeCategoryDto>> allowedWorkCodeCategories(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String locale
    ) {
        return ResponseEntity.ok(allowedCategoryService.listFor(employeeId, date, locale));
    }

    @GetMapping("/compensation-scheme-history")
    public ResponseEntity<List<EmployeeCompensationSchemeHistoryDto>> history(@PathVariable Long employeeId) {
        return ResponseEntity.ok(
                schemeService.getHistory(employeeId).stream()
                        .map(EmployeeCompensationSchemeHistoryDto::from)
                        .toList());
    }

    /**
     * Move the employee to a different scheme from a given date.
     *
     * <p>POST, not PUT: this appends a period to the history. It never overwrites
     * the employee's current scheme in place, because doing so would silently
     * change what past work was worth.
     */
    @PostMapping("/compensation-scheme-history")
    public ResponseEntity<EmployeeCompensationSchemeHistoryDto> changeScheme(
            @PathVariable Long employeeId,
            @RequestBody @Valid ChangeCompensationSchemeRequest request
    ) {
        EmployeeCompensationSchemeHistory created = schemeService.changeScheme(
                employeeId,
                request.getCompensationSchemeId(),
                request.getEffectiveFrom(),
                request.getNote());
        return ResponseEntity.ok(EmployeeCompensationSchemeHistoryDto.from(created));
    }
}
