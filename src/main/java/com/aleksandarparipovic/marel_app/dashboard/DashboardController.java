package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightJob;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
