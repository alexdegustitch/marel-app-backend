package com.aleksandarparipovic.marel_app.admin_schedule;

import com.aleksandarparipovic.marel_app.admin_schedule.dto.AdminScheduleEntryDto;
import com.aleksandarparipovic.marel_app.admin_schedule.dto.CreateAdminScheduleRequest;
import com.aleksandarparipovic.marel_app.admin_schedule.dto.UpdateAdminScheduleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * The administration's roster. Everything here — reading included — is
 * ADMIN_SCHEDULE_MANAGE, because the screen is the administration's own and
 * everybody who may see it may also change it. SecurityConfig matches the same
 * URLs, belt and braces.
 */
@RestController
@RequestMapping("/api/admin-schedule")
@RequiredArgsConstructor
public class AdminScheduleController {

    private final AdminScheduleService service;

    @GetMapping
    @PreAuthorize("@perm.has('ADMIN_SCHEDULE_MANAGE')")
    public ResponseEntity<List<AdminScheduleEntryDto>> list(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to
    ) {
        return ResponseEntity.ok(service.listBetween(from, to));
    }

    @PostMapping
    @PreAuthorize("@perm.has('ADMIN_SCHEDULE_MANAGE')")
    public ResponseEntity<List<AdminScheduleEntryDto>> create(
            @Valid @RequestBody CreateAdminScheduleRequest request
    ) {
        return ResponseEntity.ok(service.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@perm.has('ADMIN_SCHEDULE_MANAGE')")
    public ResponseEntity<AdminScheduleEntryDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAdminScheduleRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Removal archives the row; the audit trail keeps what the roster said. */
    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('ADMIN_SCHEDULE_MANAGE')")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }
}
