package com.aleksandarparipovic.marel_app.employee_bonus;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.jpa.domain.Specification;
import java.util.*;
import com.aleksandarparipovic.marel_app.employee_bonus.dto.*;

@RestController
@RequestMapping("/api/employee-bonuses")
@RequiredArgsConstructor
public class EmployeeBonusController {

    private final EmployeeBonusService employeeBonusService;

    @GetMapping
    public ResponseEntity<List<EmployeeBonusDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Boolean active
    ) {
        return ResponseEntity.ok(employeeBonusService.search(employeeId, active));
    }
}
