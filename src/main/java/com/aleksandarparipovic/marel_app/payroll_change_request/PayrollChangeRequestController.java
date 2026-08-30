package com.aleksandarparipovic.marel_app.payroll_change_request;

import com.aleksandarparipovic.marel_app.payroll_change_request.dto.PayrollChangeRequestCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_change_request.dto.PayrollChangeRequestDecisionRequest;
import com.aleksandarparipovic.marel_app.payroll_change_request.dto.PayrollChangeRequestResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Requests to reopen a payroll that has already been handed over.
 *
 * <p>Two permissions, two halves. Raising one is the supervisor's
 * (PAYROLL_CHANGE_REQUEST_CREATE); answering it is payroll's
 * (PAYROLL_CHANGE_REQUEST_PROCESS), because accepting takes the payroll to
 * DRAFT and that is a status change wearing a different name.
 *
 * <p>The LIST is open to both and narrowed by the service: whoever answers them
 * sees the queue, everybody else sees their own. Gating it by permission here
 * would either hide a requester's own requests from them or open the queue.
 */
@RestController
@RequestMapping("/api/payroll-change-requests")
@RequiredArgsConstructor
public class PayrollChangeRequestController {

    private final PayrollChangeRequestService service;

    /**
     * One page of what this reader may see.
     *
     * <p>{@code status} omitted means every status. The screen groups by status
     * and asks for one group at a time, five at first and ten more each time
     * somebody presses for them — so the page and the size are the whole of the
     * paging, and none of it happens in the browser.
     */
    @GetMapping
    public ResponseEntity<Page<PayrollChangeRequestResponse>> search(
            @RequestParam(required = false) PayrollChangeRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(service.search(status, page, size));
    }

    /** What one payroll's own page shows beside its history. */
    @GetMapping("/by-payroll-run-item/{payrollRunItemId}")
    public ResponseEntity<List<PayrollChangeRequestResponse>> forPayrollRunItem(
            @PathVariable Long payrollRunItemId) {
        return ResponseEntity.ok(service.forPayrollRunItem(payrollRunItemId));
    }

    @PostMapping
    @PreAuthorize("@perm.has('PAYROLL_CHANGE_REQUEST_CREATE')")
    public ResponseEntity<PayrollChangeRequestResponse> create(
            @Valid @RequestBody PayrollChangeRequestCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    /** Grants it: the payroll goes back to DRAFT, from APPROVED or from LOCKED. */
    @PostMapping("/{id}/accept")
    @PreAuthorize("@perm.has('PAYROLL_CHANGE_REQUEST_PROCESS')")
    public ResponseEntity<PayrollChangeRequestResponse> accept(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid PayrollChangeRequestDecisionRequest request) {
        return ResponseEntity.ok(service.accept(
                id, request == null ? null : request.getDecisionNote()));
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("@perm.has('PAYROLL_CHANGE_REQUEST_PROCESS')")
    public ResponseEntity<PayrollChangeRequestResponse> decline(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid PayrollChangeRequestDecisionRequest request) {
        return ResponseEntity.ok(service.decline(
                id, request == null ? null : request.getDecisionNote()));
    }
}
