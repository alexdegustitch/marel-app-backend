package com.aleksandarparipovic.marel_app.production_order_scope_request;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.production_order_scope_request.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Order-scope requests: which operations a production order actually needs.
 *
 * <p>Shaped exactly like {@code ManufacturingTimeRequestController}, and for the
 * same reasons — no generic update or status endpoint, every state change behind
 * a named operation that encodes a legal transition, and creator, assignee,
 * processor and timestamps taken from the security context and the server clock
 * rather than from the body.
 */
@RestController
@RequestMapping("/api/production-order-scope-requests")
@RequiredArgsConstructor
public class ProductionOrderScopeRequestController {

    private static final int MAX_PAGE_SIZE = 100;

    /** The range an unset date filter stands for: everything there could be. */
    private static final OffsetDateTime DAWN = OffsetDateTime.parse("1900-01-01T00:00:00Z");
    private static final OffsetDateTime DUSK = OffsetDateTime.parse("9999-12-31T00:00:00Z");

    private final ProductionOrderScopeRequestService service;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    /**
     * Ask the floor to work out an order's scope.
     *
     * <p>Open to whoever raises requests and closed to the supervisor, who
     * decides them — the same rule the manufacturing-time workflow follows.
     * Administrators hold the capability like everyone else, so they can raise a
     * request as well as decide one.
     */
    @PostMapping
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_CREATE')")
    public ResponseEntity<ProductionOrderScopeRequestResponse> create(
            @RequestBody @Valid ProductionOrderScopeRequestCreateRequest request
    ) {
        return ResponseEntity.ok(
                service.create(request, currentUserService.getCurrentUserId()));
    }

    /**
     * The order's lines with the notes a request about them would start from, so
     * the dialog can offer them for editing. Lines already covered by a live
     * request are left out — asking about them again would be refused on submit.
     */
    @GetMapping("/proposed-lines/{productionOrderId}")
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_CREATE')")
    public ResponseEntity<List<ProductionOrderScopeLineResponse>> proposedLines(
            @PathVariable Long productionOrderId
    ) {
        return ResponseEntity.ok(service.proposedLines(productionOrderId));
    }

    /**
     * A caller without the read-all permission is silently narrowed to their own
     * requests rather than refused — the screen is the same, the scope is not.
     */
    @GetMapping
    public ResponseEntity<Page<ProductionOrderScopeRequestResponse>> search(
            @RequestParam(required = false) ProductionOrderScopeRequestStatus status,
            @RequestParam(required = false) Long productionOrderId,
            @RequestParam(required = false) Long createdById,
            @RequestParam(required = false) Long assignedToId,
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
                permissionService.hasPermission(AppPermission.ORDER_SCOPE_REQUEST_READ_ALL)
                        ? createdById
                        : currentUserId;

        return ResponseEntity.ok(service.search(
                status, productionOrderId, effectiveCreatedBy, assignedToId,
                mine ? currentUserId : null,
                // A calendar day becomes a half-open instant range in the
                // server's own zone, so one chosen day means all of that day.
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
     * What one order's scope requests say about it. Narrowed to the caller's own
     * by the same rule as the list above.
     */
    @GetMapping("/by-production-order/{productionOrderId}")
    public ResponseEntity<List<ProductionOrderScopeRequestResponse>> byProductionOrder(
            @PathVariable Long productionOrderId
    ) {
        Long restrictTo =
                permissionService.hasPermission(AppPermission.ORDER_SCOPE_REQUEST_READ_ALL)
                        ? null
                        : currentUserService.getCurrentUserId();

        return ResponseEntity.ok(service.forProductionOrder(productionOrderId, restrictTo));
    }

    /**
     * One request with its answer — the completion modal, and the read-only view
     * of a request already handed over. Which of the two it is comes back as
     * {@code editable} rather than being re-derived by the client.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductionOrderScopeRequestDetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.getDetail(id, currentUserService.getCurrentUserId()));
    }

    /** Claim for yourself, or assign to someone else by passing assigneeUserId. */
    @PostMapping("/{id}/assign")
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_PROCESS')")
    public ResponseEntity<ProductionOrderScopeRequestResponse> assign(
            @PathVariable Long id,
            @RequestBody(required = false) ProductionOrderScopeRequestAssignRequest request
    ) {
        return ResponseEntity.ok(service.assign(
                id,
                currentUserService.getCurrentUserId(),
                request == null ? null : request.getAssigneeUserId()));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_PROCESS')")
    public ResponseEntity<ProductionOrderScopeRequestResponse> release(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.release(id, currentUserService.getCurrentUserId()));
    }

    /**
     * Save the decided scope without handing it over. The request stays in
     * review and keeps its owner, who may come back and change it.
     */
    @PutMapping("/{id}/result")
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_PROCESS')")
    public ResponseEntity<ProductionOrderScopeRequestDetailResponse> saveResult(
            @PathVariable Long id,
            @RequestBody @Valid ProductionOrderScopeResultRequest payload
    ) {
        return ResponseEntity.ok(service.saveDraft(
                id, currentUserService.getCurrentUserId(), payload));
    }

    /** Save and hand over: the request is completed and the answer becomes final. */
    @PostMapping("/{id}/complete")
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_PROCESS')")
    public ResponseEntity<ProductionOrderScopeRequestDetailResponse> complete(
            @PathVariable Long id,
            @RequestBody @Valid ProductionOrderScopeResultRequest payload
    ) {
        return ResponseEntity.ok(service.submit(
                id, currentUserService.getCurrentUserId(), payload));
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("@perm.has('ORDER_SCOPE_REQUEST_PROCESS')")
    public ResponseEntity<ProductionOrderScopeRequestResponse> decline(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ProductionOrderScopeRequestDecisionRequest decision
    ) {
        return ResponseEntity.ok(service.decline(
                id,
                currentUserService.getCurrentUserId(),
                decision == null ? null : decision.getDecisionNote()));
    }

    /** The requester withdraws their own request; a processor may also cancel. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ProductionOrderScopeRequestResponse> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ProductionOrderScopeRequestDecisionRequest decision
    ) {
        return ResponseEntity.ok(service.cancel(
                id,
                currentUserService.getCurrentUserId(),
                decision == null ? null : decision.getDecisionNote()));
    }
}
