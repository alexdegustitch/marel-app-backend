package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.employee.dto.ArchiveEmployeeRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeBasicInfoDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDetailDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDirectorySummary;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeCreateRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeEditRequest;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    public ResponseEntity<List<EmployeeDto>> search(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) LocalDate employedOn
    ) {
        return ResponseEntity.ok(employeeService.search(active, departmentId, employedOn));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeBasicInfoDto> getEmployeeById(
            @PathVariable Long id
    ){
        return ResponseEntity.ok(employeeService.getEmployeeById(id));
    }

    @PostMapping
    public ResponseEntity<EmployeeWithBonusView> createEmployee(
            @RequestBody @Valid EmployeeCreateRequest request) {

        EmployeeWithBonusView created = employeeService.createEmployee(request);

        URI location = URI.create("/employees/" + created.getEmployeeId());

        return ResponseEntity
                .created(location)
                .body(created);
    }


    @PostMapping("/search")
    public Page<EmployeeWithBonusView> search(@RequestBody SearchRequest state) {
        return employeeService.search(state);
    }

    @PostMapping("/search-all")
    public Page<EmployeeWithBonusView> searchAll(@RequestBody SearchRequest request) {
        return employeeService.searchAll(request, EmployeeWithBonusView.class);
    }

    /**
     * Headline counts for the directory — total, per scheme, on probation — for
     * the same request body {@code /search-all} takes. Pagination and sort in
     * the body are ignored; only the filters and the global search matter.
     */
    @PostMapping("/search-all/summary")
    public EmployeeDirectorySummary directorySummary(@RequestBody SearchRequest request) {
        return employeeService.directorySummary(request);
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<EmployeeDetailDto> getEmployeeDetail(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getEmployeeDetail(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EmployeeWithBonusView> patchEmployee(
            @PathVariable Long id,
            @RequestBody EmployeePatchRequest request) {
        return ResponseEntity.ok(employeeService.patchEmployee(id, request));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archiveEmployee(
            @PathVariable Long id,
            @RequestBody @Valid ArchiveEmployeeRequest request) {
        employeeService.archiveEmployee(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeWithBonusView> updateEmployee(
            @PathVariable Long id,
            @RequestBody @Valid EmployeeEditRequest request
    ) {
        EmployeeWithBonusView employee = employeeService.updateEmployee(id, request);
        return ResponseEntity.ok(employee);
    }

    @GetMapping("/table")
    public Page<EmployeeWithBonusView> getEmployeeTable(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return employeeService.getEmployeeBonusTable(PageRequest.of(page, size));
    }


}
