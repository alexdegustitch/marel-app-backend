package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.operation.dto.OperationGroupDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Groups of interchangeable operations. Reads are open like the rest of the
 * catalogue; writes carry {@code OPERATION_MANAGE} via the
 * {@code /api/operation-groups/**} matcher in {@code SecurityConfig}.
 */
@RestController
@RequiredArgsConstructor
public class OperationGroupController {

    private final OperationGroupService service;

    @GetMapping("/api/products/{productId}/operation-groups")
    public ResponseEntity<List<OperationGroupDto>> listForProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(service.listForProduct(productId));
    }

    @PostMapping("/api/operation-groups")
    public ResponseEntity<OperationGroupDto> create(@Valid @RequestBody OperationGroupCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PatchMapping("/api/operation-groups/{id}")
    public ResponseEntity<OperationGroupDto> update(
            @PathVariable Long id,
            @Valid @RequestBody OperationGroupUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Archives an EMPTY group; one with members refuses out loud. */
    @DeleteMapping("/api/operation-groups/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/operation-groups/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/operation-groups/{id}/members/{operationId}")
    public ResponseEntity<OperationGroupDto> addMember(
            @PathVariable Long id,
            @PathVariable Long operationId
    ) {
        return ResponseEntity.ok(service.addMember(id, operationId));
    }

    @DeleteMapping("/api/operation-groups/{id}/members/{operationId}")
    public ResponseEntity<OperationGroupDto> removeMember(
            @PathVariable Long id,
            @PathVariable Long operationId
    ) {
        return ResponseEntity.ok(service.removeMember(id, operationId));
    }

    /** The radio: makes this member the group's default and clears the rest. */
    @PutMapping("/api/operation-groups/{id}/default/{operationId}")
    public ResponseEntity<OperationGroupDto> setDefault(
            @PathVariable Long id,
            @PathVariable Long operationId
    ) {
        return ResponseEntity.ok(service.setDefault(id, operationId));
    }
}
