package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.MyPayrollsResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemDetailResponse;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A worker's own payslips.
 *
 * <p>Separate from {@link PayrollRunItemController} on purpose. That controller
 * is payroll's workspace, where an id in the path names whichever month the
 * clerk is working on. Here there is no such id and there must not be: the
 * worker is resolved from the SESSION, through the account's employee link, and
 * the only thing the caller may name is which of their OWN months to open.
 *
 * <p>Mounted under {@code /api/me}, which is where everything about the caller's
 * own account now lives. NOT under {@code /api/users/**}: that is admin-only in
 * {@code SecurityConfig}, and nesting self-service beneath it would have meant
 * punching per-route holes in that rule.
 *
 * <p>Both routes are reachable by any signed-in user ({@code anyRequest()
 * .authenticated()}). The access decision is not a role — every role may have a
 * payslip — it is identity, and it is made in the service against the linked
 * employee.
 */
@RestController
@RequestMapping("/api/me/payrolls")
@RequiredArgsConstructor
public class MyPayrollController {

    private final PayrollRunItemService payrollRunItemService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    /** The finished months, newest first, plus whether this account is a worker at all. */
    @GetMapping
    public ResponseEntity<MyPayrollsResponse> mine() {
        Employee employee = myEmployee();
        if (employee == null) {
            return ResponseEntity.ok(MyPayrollsResponse.notAWorker());
        }
        return ResponseEntity.ok(new MyPayrollsResponse(
                true,
                employee.getFullName(),
                employee.getEmployeeNo(),
                payrollRunItemService.lockedPayrollsOf(employee.getId())));
    }

    /**
     * One of my payslips, complete — the document the PDF is rendered from.
     *
     * @param locale optional language override for the display names. Omitted,
     *               the worker's own {@code preferred_locale} decides, which is
     *               the language payroll produces their documents in.
     */
    @GetMapping("/{monthlyReportId}")
    public ResponseEntity<PayrollRunItemDetailResponse> mineById(
            @PathVariable Long monthlyReportId,
            @RequestParam(required = false) String locale) {
        Employee employee = myEmployee();
        if (employee == null) {
            // The same refusal the service gives for somebody else's month. An
            // account with no worker behind it has no payslips to be refused
            // one OF, and saying so differently would leak that the month exists.
            throw new AccessDeniedException("Ovaj obračun nije vaš ili još nije zaključan.");
        }
        return ResponseEntity.ok(
                payrollRunItemService.ownDetails(monthlyReportId, employee.getId(), locale));
    }

    /**
     * The worker who is signed in, or null when the account is not one.
     *
     * <p>Read from the account record rather than carried in the token: the link
     * is administrative data that can be corrected at any moment, and a token
     * issued this morning must not still be pointing at the wrong person this
     * afternoon.
     */
    private Employee myEmployee() {
        Long userId = currentUserService.getCurrentUserId();
        if (userId == null) {
            throw new AccessDeniedException("Niste prijavljeni.");
        }
        return userRepository.findById(userId)
                .map(User::getEmployee)
                .orElse(null);
    }
}
