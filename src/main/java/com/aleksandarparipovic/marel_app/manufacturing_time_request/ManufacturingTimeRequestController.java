package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Manufacturing-time requests.
 *
 * <p>There is no generic update or status endpoint by design — every state change
 * goes through a named operation that encodes a legal transition. Creator,
 * assignee, processor and all timestamps come from the security context and the
 * server clock, never from the request body.
 */
@RestController
@RequestMapping("/api/manufacturing-time-requests")
@RequiredArgsConstructor
public class ManufacturingTimeRequestController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ManufacturingTimeRequestService service;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    /** Any authenticated user may ask for a manufacturing time. */
    @PostMapping
    public ResponseEntity<ManufacturingTimeRequestResponse> create(
            @RequestBody @Valid ManufacturingTimeRequestCreateRequest request
    ) {
        return ResponseEntity.ok(
                service.create(request, currentUserService.getCurrentUserId()));
    }

    /**
     * A caller without the read-all permission is silently narrowed to their own
     * requests rather than refused — the screen is the same, the scope is not.
     */
    @GetMapping
    public ResponseEntity<Page<ManufacturingTimeRequestResponse>> search(
            @RequestParam(required = false) ManufacturingTimeRequestStatus status,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long createdById,
            @RequestParam(required = false) Long assignedToId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long effectiveCreatedBy =
                permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)
                        ? createdById
                        : currentUserId;

        return ResponseEntity.ok(service.search(
                status, productId, effectiveCreatedBy, assignedToId,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"))
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ManufacturingTimeRequestResponse> getById(@PathVariable Long id) {
        ManufacturingTimeRequestResponse response = service.getById(id);

        if (!permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)
                && !response.createdByUserId().equals(currentUserService.getCurrentUserId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Nemate pristup ovom zahtevu.");
        }

        return ResponseEntity.ok(response);
    }

    /** Claim for yourself, or assign to someone else by passing assigneeUserId. */
    @PostMapping("/{id}/assign")
    @PreAuthorize("@perm.has('MANUFACTURING_TIME_REQUEST_PROCESS')")
    public ResponseEntity<ManufacturingTimeRequestResponse> assign(
            @PathVariable Long id,
            @RequestBody(required = false) ManufacturingTimeRequestAssignRequest request
    ) {
        return ResponseEntity.ok(service.assign(
                id,
                currentUserService.getCurrentUserId(),
                request == null ? null : request.getAssigneeUserId()));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("@perm.has('MANUFACTURING_TIME_REQUEST_PROCESS')")
    public ResponseEntity<ManufacturingTimeRequestResponse> release(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.release(id, currentUserService.getCurrentUserId()));
    }

    /** Completes the request and produces its manufacturing-time result atomically. */
    @PostMapping("/{id}/complete")
    @PreAuthorize("@perm.has('MANUFACTURING_TIME_REQUEST_PROCESS')")
    public ResponseEntity<ManufacturingTimeRequestResponse> complete(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ManufacturingTimeRequestDecisionRequest decision
    ) {
        return ResponseEntity.ok(service.complete(
                id, currentUserService.getCurrentUserId(), decision));
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("@perm.has('MANUFACTURING_TIME_REQUEST_PROCESS')")
    public ResponseEntity<ManufacturingTimeRequestResponse> decline(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ManufacturingTimeRequestDecisionRequest decision
    ) {
        return ResponseEntity.ok(service.decline(
                id,
                currentUserService.getCurrentUserId(),
                decision == null ? null : decision.getDecisionNote()));
    }

    /** The requester withdraws their own request; a processor may also cancel. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ManufacturingTimeRequestResponse> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ManufacturingTimeRequestDecisionRequest decision
    ) {
        return ResponseEntity.ok(service.cancel(
                id,
                currentUserService.getCurrentUserId(),
                decision == null ? null : decision.getDecisionNote()));
    }
}
