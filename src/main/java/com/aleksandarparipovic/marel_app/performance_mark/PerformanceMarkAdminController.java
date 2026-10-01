package com.aleksandarparipovic.marel_app.performance_mark;

import com.aleksandarparipovic.marel_app.common.ArchiveConfirmationRequest;
import com.aleksandarparipovic.marel_app.performance_mark.dto.PerformanceMarkAdminDto;
import com.aleksandarparipovic.marel_app.performance_mark.dto.UpsertPerformanceMarkRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The performance-marks šifarnik (ocene). Everything here is
 * APP_SETTING_MANAGE — the capability that owns the šifarnici screen — and
 * SecurityConfig matches the same URLs, belt and braces.
 */
@RestController
@RequestMapping("/api/performance-marks")
@RequiredArgsConstructor
public class PerformanceMarkAdminController {

    private final PerformanceMarkAdminService service;

    @GetMapping("/admin")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<List<PerformanceMarkAdminDto>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    /**
     * The payroll screen's picker: marks in force on {@code date} (the first
     * day of the payroll month). PAYROLL_MARK_EDIT, not APP_SETTING_MANAGE —
     * choosing a mark for somebody's month is the payroll capability, and the
     * chooser need not be allowed to administer the šifarnik itself.
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('PAYROLL_MARK_EDIT')")
    public ResponseEntity<List<com.aleksandarparipovic.marel_app.performance_mark.dto.PerformanceMarkOptionDto>> options(
            @RequestParam java.time.LocalDate date
    ) {
        return ResponseEntity.ok(service.optionsOn(date));
    }

    @PostMapping
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<PerformanceMarkAdminDto> create(@Valid @RequestBody UpsertPerformanceMarkRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<PerformanceMarkAdminDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpsertPerformanceMarkRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /**
     * Archive, signed with the caller's password — for a version that should
     * never have existed; restore undoes it.
     */
    @PostMapping("/{id}/archive")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<Void> archive(
            @PathVariable Long id,
            @Valid @RequestBody ArchiveConfirmationRequest request,
            Authentication authentication
    ) {
        service.archive(id, request.getPassword(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }
}
