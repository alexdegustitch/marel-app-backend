package com.aleksandarparipovic.marel_app.payroll_field_access;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The matrix an administrator edits: payroll lines down, roles across.
 *
 * <p>Payroll's own roles are absent from it on purpose — they see everything,
 * and offering their column would invite somebody to switch it off.
 */
@RestController
@RequestMapping("/api/payroll-field-access")
@RequiredArgsConstructor
@PreAuthorize("@perm.has('PAYROLL_ACCESS_CONFIGURE')")
public class PayrollFieldAccessController {

    /** Never offered as a column: these roles bypass the table entirely. */
    private static final Set<String> PAYROLL_ROLES = Set.of("admin", "developer");

    private final PayrollFieldAccessService service;
    private final PayrollAdjustmentCategoryRepository categoryRepository;
    private final RoleRepository roleRepository;

    public record FieldDto(String code, String name, String source) {}
    public record AccessDto(String fieldCode, String roleName, boolean canView, boolean canEdit) {}
    public record MatrixDto(List<FieldDto> fields, List<String> roles, List<AccessDto> access) {}
    public record SetAccessRequest(String fieldCode, String roleName, boolean canView, boolean canEdit) {}

    @GetMapping
    public ResponseEntity<MatrixDto> matrix() {
        List<FieldDto> fields = new java.util.ArrayList<>(categoryRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .map(c -> new FieldDto(c.getCode(), c.getName(), "ADJUSTMENT"))
                .sorted(Comparator.comparing(FieldDto::name))
                .toList());

        // The figures that are columns on the item rather than adjustment rows.
        fields.add(new FieldDto(PayrollFieldAccessService.FIELD_NET_PAYABLE, "Svega za isplatu", "ITEM"));
        fields.add(new FieldDto(PayrollFieldAccessService.FIELD_TOTAL_NET_EARNINGS, "Ukupna zarada (neto)", "ITEM"));
        fields.add(new FieldDto(PayrollFieldAccessService.FIELD_HOURLY_RATE, "Satnica", "ITEM"));

        List<String> roles = roleRepository.findAll().stream()
                .map(r -> r.getRoleName().toLowerCase())
                .filter(r -> !PAYROLL_ROLES.contains(r))
                .sorted()
                .toList();

        List<AccessDto> access = service.findAll().stream()
                .map(a -> new AccessDto(a.getFieldCode(), a.getRoleName(), a.isCanView(), a.isCanEdit()))
                .toList();

        return ResponseEntity.ok(new MatrixDto(fields, roles, access));
    }

    @PutMapping
    public ResponseEntity<AccessDto> set(@RequestBody SetAccessRequest request) {
        if (PAYROLL_ROLES.contains(request.roleName().toLowerCase())) {
            throw new IllegalArgumentException(
                    "Obračunske uloge vide sve — za njih se pristup ne podešava.");
        }
        var saved = service.set(request.fieldCode(), request.roleName(), request.canView(), request.canEdit());
        return ResponseEntity.ok(new AccessDto(
                saved.getFieldCode(), saved.getRoleName(), saved.isCanView(), saved.isCanEdit()));
    }
}
