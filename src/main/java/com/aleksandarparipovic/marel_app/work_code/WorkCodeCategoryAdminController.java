package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.work_code.dto.ReorderWorkCodeCategoriesRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.UpsertWorkCodeCategoryRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryAdminDetailDto;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryAdminDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The šifarnik's administration of work-code categories.
 *
 * <p>Every endpoint is behind {@code APP_SETTING_MANAGE} — the capability that
 * owns the šifarnici screen (admin, supervisor, developer) — both here and in
 * {@code SecurityConfig}'s matchers, so the URL rule can only narrow. The
 * everyone-signed-in read stays where it was:
 * {@code GET /active-work-code-categories} on the older controller.
 */
@RestController
@RequestMapping("api/work-code-categories")
@RequiredArgsConstructor
public class WorkCodeCategoryAdminController {

    private final WorkCodeCategoryAdminService service;

    @GetMapping("/admin")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<List<WorkCodeCategoryAdminDto>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{id}/admin")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<WorkCodeCategoryAdminDetailDto> detail(@PathVariable Long id) {
        return ResponseEntity.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<WorkCodeCategoryAdminDetailDto> create(
            @RequestBody UpsertWorkCodeCategoryRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<WorkCodeCategoryAdminDetailDto> update(
            @PathVariable Long id,
            @RequestBody UpsertWorkCodeCategoryRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @PutMapping("/display-order")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<List<WorkCodeCategoryAdminDto>> reorder(
            @RequestBody ReorderWorkCodeCategoriesRequest request) {
        return ResponseEntity.ok(service.reorder(request));
    }
}
