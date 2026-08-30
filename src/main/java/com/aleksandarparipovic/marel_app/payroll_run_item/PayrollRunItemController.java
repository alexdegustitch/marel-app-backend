package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemDetailResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.RecentPayrollSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemHandoverDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemActivityDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payroll-run-items")
@RequiredArgsConstructor
public class PayrollRunItemController {

    private final PayrollRunItemService payrollRunItemService;
    /**
     * Locking asks for the caller's password as well as their permission. Done
     * HERE rather than in the service: it is a question about the session, and a
     * service method that demanded one would demand it from every internal
     * caller that has neither.
     */
    private final com.aleksandarparipovic.marel_app.auth.StepUpVerificationService stepUp;

    // ── The performance mark (ocena) ────────────────────────────────────────
    //
    // Three endpoints rather than three fields on the patch body, because the
    // two halves are held by two different people. Folding them into patch would
    // mean one permission for the whole request and a supervisor who could apply
    // a mark by sending the right JSON.

    /**
     * Gives the mark, or takes it away with a null body value.
     *
     * <p>Changes no figure. The rate only moves when somebody APPLIES the mark,
     * which is the endpoint below and a different permission.
     */
    @PatchMapping("/{id}/performance-mark")
    @PreAuthorize("@perm.has('PAYROLL_MARK_EDIT')")
    public ResponseEntity<PayrollRunItemDetailResponse> setPerformanceMark(
            @PathVariable Long id,
            @RequestBody PerformanceMarkRequest request
    ) {
        return ResponseEntity.ok(payrollRunItemService.setPerformanceMark(id, request.mark()));
    }

    /** Puts the mark in force: the hourly rate becomes the base times the mark. */
    @PostMapping("/{id}/performance-mark/apply")
    @PreAuthorize("@perm.has('PAYROLL_MARK_APPLY')")
    public ResponseEntity<PayrollRunItemDetailResponse> applyPerformanceMark(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemService.applyPerformanceMark(id));
    }

    /** Takes it out of force. The rate returns to its base, exactly. */
    @PostMapping("/{id}/performance-mark/revert")
    @PreAuthorize("@perm.has('PAYROLL_MARK_APPLY')")
    public ResponseEntity<PayrollRunItemDetailResponse> revertPerformanceMark(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemService.revertPerformanceMark(id));
    }

    /**
     * A record rather than a bare BigDecimal, so that {@code null} can arrive as
     * a real value — {@code {"mark": null}} means "take the mark away", which a
     * plain body could not express.
     */
    public record PerformanceMarkRequest(java.math.BigDecimal mark) {
    }

    @GetMapping("/last-activity")
    public ResponseEntity<List<PayrollRunItemActivityDto>> getLastActivity(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(payrollRunItemService.getLastActivityByMonth(year, month));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<RecentPayrollSummaryDto>> getRecentByEmployee(
            @RequestParam Long employeeId,
            @RequestParam(defaultValue = "3") int size) {
        return ResponseEntity.ok(payrollRunItemService.getRecentByEmployee(employeeId, size));
    }

    @GetMapping
    public ResponseEntity<List<PayrollRunItemResponse>> findAll() {
        return ResponseEntity.ok(payrollRunItemService.findAll().stream().map(PayrollRunItemResponse::new).toList());
    }

    /**
     * Raw lookup — does NOT trigger version-based recalculation.
     * Use {@code GET /{id}/payroll} for version-aware access.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunItemResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.findById(id)));
    }

    /**
     * Payroll-aware access for a single item.
     * <p>
     * Automatically recalculates the item if {@code monthly_reports.version}
     * has advanced past {@code based_on_version}, <b>unless</b> the item is LOCKED.
     *
     * <pre>
     * GET /api/payroll-run-items/{id}/payroll
     * </pre>
     */
    @GetMapping("/{id}/payroll")
    public ResponseEntity<PayrollRunItemResponse> getForPayrollAccess(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.getForPayrollAccess(id)));
    }

    /**
     * @param locale optional override for the document language. Omitted, the
     *               employee's own preferred_locale is used — a payslip is a
     *               document about the employee, not about the clerk opening it.
     *               It selects display names only; every amount is identical in
     *               every locale.
     */
    /*
     * The response is Object because one of the two bodies is a stored document.
     *
     * A reader who has handed this payroll over gets the copy they submitted,
     * replayed from the handover record rather than rebuilt from live rows. It
     * serialises to the same shape as the live response — same fields, same
     * names — so the client cannot tell them apart, which is the intent.
     */
    @GetMapping("/by-monthly-report/{monthlyReportId}/details")
    public ResponseEntity<Object> getDetails(
            @PathVariable Long monthlyReportId,
            @RequestParam(required = false) String locale) {
        return payrollRunItemService.frozenDetails(monthlyReportId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(
                        payrollRunItemService.getDetails(monthlyReportId, locale)));
    }

    /**
     * Returns all items for a payroll run, recalculating any that are stale.
     * LOCKED items are returned as-is.
     *
     * <pre>
     * GET /api/payroll-run-items/by-run/{runId}
     * </pre>
     */
    @GetMapping("/by-run/{runId}")
    public ResponseEntity<List<PayrollRunItemResponse>> getForPayrollRun(@PathVariable Long runId) {
        return ResponseEntity.ok(payrollRunItemService.getForPayrollRun(runId).stream().map(PayrollRunItemResponse::new).toList());
    }

    @PostMapping
    public ResponseEntity<PayrollRunItemResponse> create(@Valid @RequestBody PayrollRunItemCreateRequest request) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.create(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PayrollRunItemDetailResponse> patch(
            @PathVariable Long id,
            @RequestBody PayrollRunItemPatchRequest request) {
        return ResponseEntity.ok(payrollRunItemService.patch(id, request));
    }

    /**
     * Freeze the item: no recalculation touches it again.
     *
     * <p>POST rather than PATCH because it is an event, not a field. Refused with a
     * list of the lines still waiting for input — a month must not be frozen with a
     * zero nobody decided on.
     */
    /**
     * Freezes the month.
     *
     * <p>Carries the caller's own password on top of PAYROLL_LOCK. Locking
     * publishes the payroll to the employee and closes it to every further edit,
     * and it cannot be taken back by simply doing the opposite — reopening
     * leaves both steps in the history for good. Verified BEFORE anything else,
     * so a wrong password costs no work and changes nothing.
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("@perm.has('PAYROLL_LOCK')")
    public ResponseEntity<PayrollRunItemResponse> lock(
            @PathVariable Long id,
            @RequestBody(required = false) LockRequest request) {
        stepUp.requireCurrentPassword(request == null ? null : request.password());
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.lock(id)));
    }

    /** The password of the account pressing the button, nobody else's. */
    public record LockRequest(String password) {
    }

    /**
     * Hand the month over to payroll — "spreman".
     *
     * <p>Supervisors as well as admins: this is the shop floor saying its work is
     * done, and the supervisor is the one who knows. Payroll's own step is the
     * lock, which stays admin-only.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("@perm.has('PAYROLL_HANDOVER')")
    public ResponseEntity<PayrollRunItemResponse> submit(
            @PathVariable Long id,
            @RequestBody(required = false) HandoverRequest request) {
        String note = request == null ? null : request.note();
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.submit(id, note)));
    }

    /** Send it back for correction. Same people who may hand it over. */
    /**
     * Payroll handing a submitted month back of its own accord.
     *
     * <p>PAYROLL_LOCK, not PAYROLL_HANDOVER: once the month is submitted, payroll
     * may already have worked on it, and taking it back underneath them
     * invalidates that. The supervisor's way back is a change request, which
     * payroll answers. PAYROLL_HANDOVER still lets them SUBMIT — that half is
     * unchanged.
     */
    @PostMapping("/{id}/return-to-draft")
    @PreAuthorize("@perm.has('PAYROLL_LOCK')")
    public ResponseEntity<PayrollRunItemResponse> returnToDraft(
            @PathVariable Long id,
            @RequestBody(required = false) HandoverRequest request) {
        String note = request == null ? null : request.note();
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.returnToDraft(id, note)));
    }

    /** Every handover step of one item, newest first. */
    @GetMapping("/{id}/handovers")
    public ResponseEntity<List<PayrollRunItemHandoverDto>> handovers(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemService.getHandovers(id));
    }

    /** The payroll exactly as it stood at one handover. */
    @GetMapping("/{id}/handovers/{handoverId}/snapshot")
    public ResponseEntity<Map<String, Object>> handoverSnapshot(
            @PathVariable Long id, @PathVariable Long handoverId) {
        return payrollRunItemService.getHandoverSnapshot(handoverId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Optional note; on a return it is the reason. */
    public record HandoverRequest(String note) {
    }

    /** Reopen a frozen item. Separate permission, separate audit entry. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("@perm.has('PAYROLL_LOCK')")
    public ResponseEntity<PayrollRunItemResponse> unlock(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.unlock(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunItemResponse> update(@PathVariable Long id, @RequestBody PayrollRunItem entity) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.update(id, entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunItemService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
