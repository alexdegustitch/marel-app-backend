package com.aleksandarparipovic.marel_app.compensation_scheme;

import com.aleksandarparipovic.marel_app.common.ArchiveConfirmationRequest;
import com.aleksandarparipovic.marel_app.compensation_scheme.dto.CompensationSchemeDto;
import com.aleksandarparipovic.marel_app.compensation_scheme.dto.UpdateSchemeNoteRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/compensation-schemes")
@RequiredArgsConstructor
public class CompensationSchemeController {

    private final CompensationSchemeRepository repository;
    private final CompensationSchemeAdminService adminService;

    /**
     * The schemes an administrator may assign — active and not archived.
     *
     * <p>{@code includeArchived} exists for the šifarnik screen, which lists
     * archived schemes so they can be restored. Every other caller (the employee
     * pickers) keeps the old answer: an archived scheme is simply gone.
     */
    @GetMapping
    public ResponseEntity<List<CompensationSchemeDto>> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        if (includeArchived) {
            return ResponseEntity.ok(adminService.listAll());
        }
        return ResponseEntity.ok(
                repository.findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc().stream()
                        .map(CompensationSchemeDto::from)
                        .toList());
    }

    /** The note is the one editable field — codes and flags stay seeded constants. */
    @PatchMapping("/{id}/note")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<CompensationSchemeDto> updateNote(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSchemeNoteRequest request) {
        return ResponseEntity.ok(adminService.updateNote(id, request.getNote()));
    }

    /**
     * Archive, signed with the caller's password. Refused while any employee is
     * on the scheme today or in the future; payroll history keeps referencing an
     * archived scheme, restore undoes it.
     */
    @PostMapping("/{id}/archive")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<Void> archive(
            @PathVariable Long id,
            @Valid @RequestBody ArchiveConfirmationRequest request,
            Authentication authentication) {
        adminService.archive(id, request.getPassword(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        adminService.restore(id);
        return ResponseEntity.noContent().build();
    }
}
