package com.aleksandarparipovic.marel_app.work_code_category_mappings;

import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingCreateRequest;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingDto;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/work-code-category-mappings")
@RequiredArgsConstructor
public class WorkCodeCategoryMappingController {

    private final WorkCodeCategoryMappingService service;

    @GetMapping
    public ResponseEntity<List<WorkCodeCategoryMappingDto>> getAllActiveMappings() {
        return ResponseEntity.ok(service.getAllActiveMappings());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkCodeCategoryMappingDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    public ResponseEntity<WorkCodeCategoryMappingDto> create(@Valid @RequestBody WorkCodeCategoryMappingCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkCodeCategoryMappingDto> update(@PathVariable Long id,
                                                             @Valid @RequestBody WorkCodeCategoryMappingUpdateRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<WorkCodeCategoryMappingDto> archive(@PathVariable Long id) {
        return ResponseEntity.ok(service.archive(id));
    }
}

