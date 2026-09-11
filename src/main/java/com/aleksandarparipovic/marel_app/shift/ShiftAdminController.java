package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.shift.dto.EmployeeShiftTimePeriodDto;
import com.aleksandarparipovic.marel_app.shift.dto.ShiftAdminDto;
import com.aleksandarparipovic.marel_app.shift.dto.UpsertShiftRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The šifarnik's administration of shifts.
 *
 * <p>Every endpoint is behind {@code APP_SETTING_MANAGE} — the capability that
 * owns the šifarnici screen — both here and in {@code SecurityConfig}'s
 * matchers, so the URL rule can only narrow. The everyone-signed-in read stays
 * where it was: {@code GET /active-shifts} on the older controller.
 */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftAdminController {

    private final ShiftAdminService service;

    @GetMapping("/admin")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<List<ShiftAdminDto>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    /** The default-hours history of one shift, newest first. */
    @GetMapping("/{id}/default-history")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<List<EmployeeShiftTimePeriodDto>> defaultHistory(@PathVariable Long id) {
        return ResponseEntity.ok(service.defaultHistory(id));
    }

    @PostMapping
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<ShiftAdminDto> create(@Valid @RequestBody UpsertShiftRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@perm.has('APP_SETTING_MANAGE')")
    public ResponseEntity<ShiftAdminDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpsertShiftRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }
}
