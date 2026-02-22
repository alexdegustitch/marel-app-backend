package com.aleksandarparipovic.marel_app.department;

import com.aleksandarparipovic.marel_app.department.dto.DepartmentCreateRequest;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @PostMapping
    public ResponseEntity<DepartmentDto> createDepartment(
            @Valid @RequestBody DepartmentCreateRequest request
    ) {
        DepartmentDto dto = departmentService.createDepartment(request);
        return ResponseEntity.ok(dto);
    }

    @GetMapping
    public ResponseEntity<Page<DepartmentDto>> getDepartments(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "id") String sortBy
    ) {
        Page<DepartmentDto> result = departmentService.getDepartments(
                name, active, page, size, direction, sortBy
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/all")
    public ResponseEntity<List<DepartmentDto>> getAllDepartments() {
        List<DepartmentDto> result = departmentService.getAllDepartments();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/active-departments")
    @Cacheable("department-options")
    public ResponseEntity<List<DepartmentOptionDto>> getAllActiveDepartments() {
        List<DepartmentOptionDto> result = departmentService.getAllActiveDepartments();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DepartmentDto> getDepartment(@PathVariable Long id) {
        DepartmentDto dto = departmentService.getDepartment(id);
        return ResponseEntity.ok(dto);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DepartmentDto> updateDepartment(
            @PathVariable Long id,
            @Valid @RequestBody DepartmentUpdateRequest request
    ) {
        DepartmentDto dto = departmentService.updateDepartment(id, request);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDepartment(@PathVariable Long id) {
        departmentService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    public ResponseEntity<Void> restoreDepartment(@PathVariable Long id) {
        departmentService.restore(id);
        return ResponseEntity.noContent().build();
    }
}
