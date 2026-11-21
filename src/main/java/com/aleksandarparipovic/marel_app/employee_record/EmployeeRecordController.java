package com.aleksandarparipovic.marel_app.employee_record;


import com.aleksandarparipovic.marel_app.employee_record.dto.*;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.ArchivePreview;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.ArchiveRequest;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.ArchiveSummary;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.MonthArchivePreview;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordArchiveDtos.MonthArchiveRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/employee-records")
public class EmployeeRecordController {

    private final EmployeeRecordService service;
    private final EmployeeRecordArchiveService archiveService;
    private final com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService passwordConfirmationService;
    private final com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeaveService employeeLeaveService;

    @GetMapping("/last-activity")
    public ResponseEntity<List<EmployeeRecordDto>> getLastWorkShifts(@RequestParam Integer year,
                                                                     @RequestParam Integer month){
        return ResponseEntity.ok(service.findLastThreePerMonthForSupervisor(year, month));
    }

    /**
     * GET /api/employee-records/year-overview?year= — the whole year in one
     * answer: twelve months, their totals, and the kartoni the caller last had
     * open in each. What the Kartoni year view reads instead of thirteen calls.
     */
    @GetMapping("/year-overview")
    public ResponseEntity<EmployeeRecordYearOverview> getYearOverview(@RequestParam Integer year) {
        return ResponseEntity.ok(service.getYearOverview(year));
    }

    /**
     * GET /api/employee-records/search?year=&q=&size= — the kartoni of one year
     * whose worker's name or number contains {@code q}. A way to open a karton
     * by typing, without first choosing the month.
     */
    @GetMapping("/search")
    public ResponseEntity<List<EmployeeRecordSearchHit>> search(
            @RequestParam Integer year,
            @RequestParam String q,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(service.searchInYear(year, q, size));
    }

    @GetMapping
    public ResponseEntity<Page<EmployeeRecordListRow>> getEmployeeRecordsByYearAndMonth(
            @RequestParam Integer year,
            @RequestParam Integer month,
            @RequestParam(required = false) String globalSearch,
            @RequestParam(required = false, defaultValue = "false") boolean onlyArchived,
            Pageable pageable
    ) {
        return ResponseEntity.ok(
                service.getEmployeeRecordsByYearAndMonth(year, month, globalSearch, onlyArchived, pageable));
    }

    /**
     * GET /api/employee-records/missing?year=&month=&globalSearch=&limit= — the
     * active workers this month has no karton for: how many, and who the first
     * of them are. The same count the create button would act on.
     */
    @GetMapping("/missing")
    public ResponseEntity<EmployeeRecordMissing> getEmployeesWithoutRecord(
            @RequestParam Integer year,
            @RequestParam Integer month,
            @RequestParam(required = false) String globalSearch,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.getEmployeesWithoutRecord(year, month, globalSearch, limit));
    }

    @GetMapping("/exists")
    public ResponseEntity<Boolean> existsForEmployeeAndMonth(
            @RequestParam Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ResponseEntity.ok(service.existsForEmployeeAndMonth(employeeId, year, month));
    }

    /**
     * The karton for one employee and month, addressed the way a calendar knows
     * them. 204 when there is none, so the caller can offer the link or not
     * without treating "no karton yet" as a failure.
     */
    @GetMapping("/by-month")
    public ResponseEntity<EmployeeRecordRefDto> getByEmployeeAndMonth(
            @RequestParam Long employeeId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return service.findRecordIdForEmployeeAndMonth(employeeId, year, month)
                .map(id -> ResponseEntity.ok(new EmployeeRecordRefDto(id, employeeId, year, month)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeRecordEmployeeInfo> getByEmployeeRecordId(@PathVariable Long id) {
        return ResponseEntity.ok(service.getByEmployeeRecordId(id));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<RecentEmployeeRecordDto>> getRecentByEmployeeId(
            @RequestParam Long employeeId,
            @RequestParam(defaultValue = "3") int size) {
        return ResponseEntity.ok(service.getRecentByEmployeeId(employeeId, size));
    }
    
    /**
     * Create the month's kartoni, then materialise the leave days that open
     * od–do periods owe the month — a sick leave entered in January continues
     * into February the moment February exists.
     *
     * <p>Composed HERE rather than inside either service: the leave service
     * already leans on the record service to reach a karton, so the record
     * service calling back into it would be a circle. Conflicting shifts are
     * only REPORTED in the response — archiving them needs a person's consent.
     */
    @PostMapping("/create-records")
    public ResponseEntity<EmployeeRecordCreateResponse> createEmployeeRecordsForMonth(
            @Valid @RequestBody EmployeeRecordCreateRequest request
    ) {
        EmployeeRecordCreateResponse createdRecords =
                service.createEmployeeRecordsForMonth(request.getYear(), request.getMonth());
        var leave = employeeLeaveService.materializeForMonthAll(request.getYear(), request.getMonth());
        return ResponseEntity.ok(createdRecords.withLeave(leave.createdShifts(), leave.conflicts()));
    }

    // ── Taking a karton back ─────────────────────────────────────────────────

    /**
     * What withdrawing this karton would touch — asked before the dialog, so the
     * person confirms a number rather than a guess. Also says whether the
     * withdrawal must be signed, and whether the karton is empty enough to be
     * deleted outright instead.
     */
    @PreAuthorize("@perm.has('EMPLOYEE_RECORD_ARCHIVE')")
    @GetMapping("/{id}/archive-preview")
    public ResponseEntity<ArchivePreview> previewArchive(@PathVariable Long id) {
        return ResponseEntity.ok(archiveService.preview(id));
    }

    /**
     * Withdraw a whole karton — one worker's month — and the live shifts in it.
     *
     * <p>Signed with the caller's password when it actually removes work; an
     * empty karton is a mistake being tidied up and needs no signature. The
     * preview says which case the screen is in, and this re-checks it rather
     * than trusting the screen.
     *
     * <p>Refused while the month's payroll is approved, sent or locked.
     */
    @PreAuthorize("@perm.has('EMPLOYEE_RECORD_ARCHIVE')")
    @PostMapping("/{id}/archive")
    public ResponseEntity<ArchiveSummary> archive(@PathVariable Long id,
                                                  @Valid @RequestBody(required = false) ArchiveRequest request,
                                                  Authentication authentication) {
        ArchiveRequest body = request != null ? request : new ArchiveRequest(null, null);
        if (archiveService.preview(id).requiresPassword()) {
            passwordConfirmationService.confirm(authentication, body.password());
        }
        return ResponseEntity.ok(archiveService.archive(id, body.reason()));
    }

    /** Put a withdrawn karton back, with the shifts that went down with it. */
    @PreAuthorize("@perm.has('EMPLOYEE_RECORD_ARCHIVE')")
    @PostMapping("/{id}/restore")
    public ResponseEntity<ArchiveSummary> restore(@PathVariable Long id) {
        return ResponseEntity.ok(archiveService.restore(id));
    }

    /**
     * Delete a karton that never held anything — no shifts at all, no monthly
     * report, no payroll item. Anything else is archived; the database enforces
     * that too, since the child tables do not cascade.
     */
    @PreAuthorize("@perm.has('EMPLOYEE_RECORD_ARCHIVE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmpty(@PathVariable Long id) {
        archiveService.deleteEmpty(id);
        return ResponseEntity.noContent().build();
    }

    /** What a month-wide withdrawal would touch, before the password dialog. */
    @PreAuthorize("@perm.has('EMPLOYEE_RECORD_ARCHIVE')")
    @GetMapping("/archive-month/preview")
    public ResponseEntity<MonthArchivePreview> previewArchiveMonth(@RequestParam Integer year,
                                                                   @RequestParam Integer month) {
        return ResponseEntity.ok(archiveService.previewMonth(year, month));
    }

    /**
     * Withdraw every live karton of one month, signed with the caller's
     * password — for a month opened by mistake. The whole month or none of it:
     * one karton whose payroll is closed refuses the entire request.
     */
    @PreAuthorize("@perm.has('EMPLOYEE_RECORD_ARCHIVE')")
    @PostMapping("/archive-month")
    public ResponseEntity<ArchiveSummary> archiveMonth(@Valid @RequestBody MonthArchiveRequest request,
                                                       Authentication authentication) {
        passwordConfirmationService.confirm(authentication, request.password());
        return ResponseEntity.ok(archiveService.archiveMonth(request.year(), request.month(), request.reason()));
    }
}
