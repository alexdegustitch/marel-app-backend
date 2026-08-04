package com.aleksandarparipovic.marel_app.employee_payroll_value;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.employee_payroll_value.dto.ChangeEmployeePayrollValueRequest;
import com.aleksandarparipovic.marel_app.employee_payroll_value.dto.EmployeePayrollValueDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Per-employee payroll values, mounted under the existing {@code /api/employees}
 * namespace rather than a parallel one.
 *
 * <p>Thin by design: validation and the close-then-open sequence live in
 * {@link EmployeePayrollValueService}, because they are the rules, not plumbing.
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/payroll-values")
@RequiredArgsConstructor
public class EmployeePayrollValueController {

    private final EmployeePayrollValueService valueService;
    private final CurrentUserService currentUserService;

    /**
     * @param code optional filter; omit for every value this employee has.
     */
    @GetMapping
    public ResponseEntity<List<EmployeePayrollValueDto>> history(
            @PathVariable Long employeeId,
            @RequestParam(required = false) String code
    ) {
        return ResponseEntity.ok(valueService.getHistory(employeeId, code).stream()
                .map(EmployeePayrollValueDto::new)
                .toList());
    }

    /**
     * Set a new value from a given date.
     *
     * <p>POST, not PUT: this appends a period. It never overwrites the current
     * value in place, because doing so would silently reprice months already
     * calculated.
     *
     * <p>Takes either a numeric value or a boolean one, and the definition's own
     * type decides which is legal — sending the wrong one is refused by the
     * service, not silently coerced. The boolean half exists because
     * TRANSPORT_PER_DAY decides whether an employee is paid transport at all: a
     * value nobody can grant or withdraw through the application would be a rule
     * only a database client could administer.
     */
    @PostMapping
    public ResponseEntity<EmployeePayrollValueDto> change(
            @PathVariable Long employeeId,
            @Valid @RequestBody ChangeEmployeePayrollValueRequest request
    ) {
        if ((request.getNumericValue() == null) == (request.getBooleanValue() == null)) {
            throw new IllegalArgumentException(
                    "Unesite tačno jednu vrednost — brojčanu ili logičku.");
        }

        EmployeePayrollValueHistory created = request.getBooleanValue() != null
                ? valueService.changeFlag(
                        employeeId,
                        request.getCode(),
                        request.getBooleanValue(),
                        request.getEffectiveFrom(),
                        request.getNote(),
                        currentUserService.getCurrentUserId())
                : valueService.changeValue(
                        employeeId,
                        request.getCode(),
                        request.getNumericValue(),
                        request.getEffectiveFrom(),
                        request.getNote(),
                        currentUserService.getCurrentUserId());

        return ResponseEntity.ok(new EmployeePayrollValueDto(created));
    }
}
