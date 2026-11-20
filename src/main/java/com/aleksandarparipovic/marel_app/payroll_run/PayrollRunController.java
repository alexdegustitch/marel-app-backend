package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunResponse;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSearchHit;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollYearOverview;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-runs")
@RequiredArgsConstructor
public class PayrollRunController {

    private final PayrollRunService payrollRunService;
    private final com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService payrollRunItemService;
    /** The password question belongs to the HTTP boundary, as on lock. */
    private final com.aleksandarparipovic.marel_app.auth.StepUpVerificationService stepUp;

    /**
     * Hand the whole month over to payroll: every APPROVED obračun → SENT.
     *
     * <p>Offered to the supervisors once the month is complete — no drafts, and
     * no employed worker without an obračun — and taken under the sender's own
     * password. One act, one notification to payroll.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{year}/{month}/send")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.has('PAYROLL_HANDOVER')")
    public ResponseEntity<SendMonthResponse> sendMonth(
            @PathVariable int year,
            @PathVariable int month,
            @RequestBody(required = false) SendMonthRequest request) {
        stepUp.requireCurrentPassword(request == null ? null : request.password());
        return ResponseEntity.ok(new SendMonthResponse(payrollRunItemService.sendMonth(year, month)));
    }

    /** The password of the account pressing the button, nobody else's. */
    public record SendMonthRequest(String password) {
    }

    /** How many obračuna actually moved to SENT by this press. */
    public record SendMonthResponse(int sentCount) {
    }

    /**
     * Whether the month can be handed over as a whole, and if not, why not.
     * For the people who would press the button, not for payroll.
     */
    @GetMapping("/{year}/{month}/readiness")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.has('PAYROLL_HANDOVER')")
    public ResponseEntity<PayrollRunService.PayrollMonthReadiness> readiness(
            @PathVariable int year, @PathVariable int month) {
        return ResponseEntity.ok(payrollRunService.getReadiness(year, month));
    }

    /**
     * The handed-over payrolls of one month — the director's list, sortable by
     * the worker's number or by when the month was last approved.
     */
    @GetMapping("/{year}/{month}/sent")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.has('PAYROLL_LOCK')")
    public ResponseEntity<Page<com.aleksandarparipovic.marel_app.payroll_run_item.dto.SentPayrollRow>> sent(
            @PathVariable int year,
            @PathVariable int month,
            @RequestParam(defaultValue = "employeeNo") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(payrollRunService.getSentByYearAndMonth(year, month, sort, dir, page, size));
    }

    /** Months of the year that hold handed-over payrolls, with how many. */
    @GetMapping("/{year}/sent-months")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.has('PAYROLL_LOCK')")
    public ResponseEntity<List<com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository.MonthSentCount>> sentMonths(
            @PathVariable int year) {
        return ResponseEntity.ok(payrollRunService.getMonthsWithSent(year));
    }

    /** GET /api/payroll-runs/years — years that actually hold obračuni, newest first. */
    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getYearsWithRuns() {
        return ResponseEntity.ok(payrollRunService.getYearsWithRuns());
    }

    /** GET /api/payroll-runs/year/{year} — summaries per employee for entire year */
    @GetMapping("/year/{year}")
    public ResponseEntity<List<PayrollRunSummaryDto>> getSummariesByYear(@PathVariable int year) {
        return ResponseEntity.ok(payrollRunService.getSummariesByYear(year));
    }

    /**
     * GET /api/payroll-runs/year-overview?year= — the whole year in one answer:
     * twelve months, their counts by status, their sums, and the obračuni the
     * caller last had open. Sums and the locked count are withheld from anyone
     * without payroll access, in the response.
     */
    @GetMapping("/year-overview")
    public ResponseEntity<PayrollYearOverview> getYearOverview(@RequestParam int year) {
        return ResponseEntity.ok(payrollRunService.getYearOverview(year));
    }

    /**
     * GET /api/payroll-runs/search?year=&q=&size= — the obračuni of one year
     * whose worker's name or number contains {@code q}.
     */
    @GetMapping("/search")
    public ResponseEntity<List<PayrollRunSearchHit>> search(
            @RequestParam int year,
            @RequestParam String q,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(payrollRunService.searchInYear(year, q, size));
    }

    /** GET /api/payroll-runs/last-activity?year=&month= — last 3 items touched by current user */
    @GetMapping("/last-activity")
    public ResponseEntity<List<PayrollRunSummaryDto>> getLastActivity(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(payrollRunService.getLastActivity(year, month));
    }

    /** GET /api/payroll-runs?year=&month=&page=&size=&globalSearch=&sort=&status= — paged per-employee list */
    @GetMapping(params = {"year", "month"})
    public ResponseEntity<Page<PayrollRunInfoDto>> getPagedByYearAndMonth(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String globalSearch,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return ResponseEntity.ok(payrollRunService.getPagedByYearAndMonth(year, month, globalSearch, status, pageable));
    }

    @GetMapping
    public ResponseEntity<List<PayrollRunResponse>> findAll() {
        return ResponseEntity.ok(payrollRunService.findAll().stream().map(PayrollRunResponse::new).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<PayrollRunResponse> create(@Valid @RequestBody PayrollRunCreateRequest request) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunResponse> update(@PathVariable Long id, @RequestBody PayrollRun entity) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.update(id, entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
