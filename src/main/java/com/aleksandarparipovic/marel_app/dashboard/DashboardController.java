package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.MissingShiftsResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightComputeService;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightJob;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The control boards. One endpoint per audience, because what a board shows IS
 * the audience — a shared endpoint filtered per role would end up answering
 * everybody's question badly.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final AdminDashboardService adminDashboardService;
    private final SupervisorDashboardService supervisorDashboardService;
    private final DashboardInsightComputeService insightComputeService;
    private final DashboardInsightJob insightJob;
    private final CurrentUserService currentUserService;

    @GetMapping("/admin")
    @PreAuthorize("@perm.has('DASHBOARD_ADMIN_VIEW')")
    public ResponseEntity<AdminDashboardResponse> admin() {
        return ResponseEntity.ok(adminDashboardService.load());
    }

    @GetMapping("/supervisor")
    @PreAuthorize("@perm.has('DASHBOARD_SUPERVISOR_VIEW')")
    public ResponseEntity<SupervisorDashboardResponse> supervisor() {
        return ResponseEntity.ok(
                supervisorDashboardService.load(currentUserService.getCurrentUserId()));
    }

    /**
     * The employees the day has no entry for — the board's drawer worklist.
     *
     * <p>Read live, not from the board's payload: the drawer is acted on row by
     * row, and every action wants to see what the previous one (or a colleague)
     * just did.
     */
    @GetMapping("/missing-shifts")
    @PreAuthorize("@perm.has('DASHBOARD_SUPERVISOR_VIEW')")
    public ResponseEntity<MissingShiftsResponse> missingShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(supervisorDashboardService.missingShifts(date));
    }

    /**
     * The "Šta se radilo" lists, live, for a chosen window of days.
     *
     * <p>The board calls this instead of reading the snapshot for these three
     * lists, because the window is personal: the default is a Parametri
     * setting, and each user may look further or closer without waiting for
     * tomorrow's job.
     */
    @GetMapping("/activity")
    @PreAuthorize("@perm.has('DASHBOARD_SUPERVISOR_VIEW')")
    public ResponseEntity<DashboardInsightComputeService.Activity> activity(
            @RequestParam(required = false) Integer windowDays) {
        return ResponseEntity.ok(insightComputeService.activity(LocalDate.now(), windowDays));
    }

    /**
     * Recompute today's analytics snapshot now.
     *
     * <p>Not what the board calls — the board only ever reads. This is here so a
     * threshold that produced an empty card can be tried out without waiting for
     * tomorrow morning's run.
     */
    @PostMapping("/insights/recompute")
    @PreAuthorize("@perm.has('DASHBOARD_INSIGHTS_RECOMPUTE')")
    public ResponseEntity<Void> recomputeInsights() {
        insightJob.recomputeNow();
        return ResponseEntity.noContent().build();
    }
}
