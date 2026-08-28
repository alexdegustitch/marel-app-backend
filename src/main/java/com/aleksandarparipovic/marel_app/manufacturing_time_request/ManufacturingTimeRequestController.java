package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.ZoneId;
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

    /** The range an unset date filter stands for: everything there could be. */
    private static final java.time.OffsetDateTime DAWN =
            java.time.OffsetDateTime.parse("1900-01-01T00:00:00Z");
    private static final java.time.OffsetDateTime DUSK =
            java.time.OffsetDateTime.parse("9999-12-31T00:00:00Z");

    private final ManufacturingTimeRequestService service;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    /**
     * Ask for a manufacturing time.
     *
     * <p>Open to everybody EXCEPT the supervisor, who decides requests — whoever
     * decides them does not raise them, which is the rule the requests screen has
     * always shown and this is what enforces it. Administrators hold the
     * capability like everyone else, so they can raise a request as well as
     * decide one.
     */
    @PostMapping
    @PreAuthorize("@perm.has('MANUFACTURING_TIME_REQUEST_CREATE')")
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
            @RequestParam(required = false) Long productionOrderId,
            /** Narrow to what this caller raised or took on. */
            @RequestParam(defaultValue = "false") boolean mine,
            /** Inclusive calendar bounds on when the request was raised. */
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "true") boolean newestFirst,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long effectiveCreatedBy =
                permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)
                        ? createdById
                        : currentUserId;

        return ResponseEntity.ok(service.search(
                status, productId, effectiveCreatedBy, assignedToId, productionOrderId,
                mine ? currentUserId : null,
                // A calendar day becomes a half-open instant range in the
                // server's own zone, so "23. avgust" means all of that day.
                createdFrom == null
                        ? DAWN
                        : createdFrom.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime(),
                createdTo == null
                        ? DUSK
                        : createdTo.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime(),
                // The query ranks the statuses into groups; this sort is appended
                // after that rank, so it orders WITHIN each group.
                PageRequest.of(
                        Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(newestFirst ? Sort.Direction.DESC : Sort.Direction.ASC, "createdAt"))
        ));
    }

    /**
     * The picker on the manufacturing-time screen: work free to take, plus work
     * this caller already took. Narrowed to their own requests by the same rule
     * as the list above.
     */
    @GetMapping("/open")
    public ResponseEntity<java.util.List<ManufacturingTimeRequestResponse>> open() {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long restrictTo =
                permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)
                        ? null
                        : currentUserId;

        return ResponseEntity.ok(service.pickableRequests(currentUserId, restrictTo));
    }

    /**
     * What one production order's lines can say about their manufacturing time:
     * a request still running, or the answer one already got. Narrowed to the
     * caller's own requests by the same rule as the list above.
     */
    @GetMapping("/open-by-production-order/{productionOrderId}")
    public ResponseEntity<java.util.List<ManufacturingTimeRequestResponse>> openByProductionOrder(
            @PathVariable Long productionOrderId
    ) {
        Long restrictTo =
                permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)
                        ? null
                        : currentUserService.getCurrentUserId();

        return ResponseEntity.ok(service.forProductionOrder(productionOrderId, restrictTo));
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
