package com.aleksandarparipovic.marel_app.employee_bonus;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_bonus.dto.ChangeBonusRequest;
import com.aleksandarparipovic.marel_app.employee_bonus.dto.ChangeBonusResponse;
import com.aleksandarparipovic.marel_app.recalc_queue.AffectedMonthsRecalculator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/employees/{employeeId}")
@RequiredArgsConstructor
public class EmployeeBonusController {

    private final EmployeeBonusService bonusService;
    private final EmployeeRepository employeeRepository;
    private final BonusCategoryRepository bonusCategoryRepository;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    /**
     * Move the employee to a different bonus category for a dated range.
     *
     * <p>Returns 200 even when every affected month was locked and nothing could
     * be recalculated — the edit itself succeeded, and the message names the
     * months that were left alone. Refusing there would block a correction to
     * future payroll because of past payroll nobody can change.
     */
    @PostMapping("/bonus-history")
    public ResponseEntity<ChangeBonusResponse> changeBonus(
            @PathVariable Long employeeId,
            @Valid @RequestBody ChangeBonusRequest request
    ) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));

        BonusCategory category = bonusCategoryRepository.findById(request.getBonusCategoryId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Bonus kategorija ne postoji: " + request.getBonusCategoryId()));

        AffectedMonthsRecalculator.Result result = bonusService.changeBonus(
                employee, category, request.getValidFrom(), request.getValidTo(),
                // Who made the change, for the audit trail on the bonus row.
                userRepository.findById(currentUserService.getCurrentUserId()).orElse(null));

        return ResponseEntity.ok(new ChangeBonusResponse(
                format(result.recalculated()),
                format(result.skippedLocked()),
                result.messageOrEmpty()));
    }

    private static List<String> format(List<YearMonth> months) {
        return months.stream().map(m -> m.getMonthValue() + "/" + m.getYear()).toList();
    }
}
