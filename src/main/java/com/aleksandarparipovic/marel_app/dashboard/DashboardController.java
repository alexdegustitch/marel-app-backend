package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/admin")
    @PreAuthorize("@perm.has('DASHBOARD_ADMIN_VIEW')")
    public ResponseEntity<AdminDashboardResponse> admin() {
        return ResponseEntity.ok(adminDashboardService.load());
    }
}
