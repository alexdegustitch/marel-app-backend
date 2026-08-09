package com.aleksandarparipovic.marel_app.payroll_field_access;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollVisibilityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which payroll lines the caller may see and change.
 *
 * <p>The single place that answers it. Payroll's own roles bypass the table:
 * they see and edit everything, which is what lets a MISSING row mean hidden
 * and keeps an administrator from locking payroll out of payroll by editing a
 * screen.
 *
 * <p>Everyone else is answered from configuration, and the default is NO. That
 * matters on the day a new adjustment category is added: it is invisible to
 * everybody outside payroll until somebody says otherwise, rather than
 * appearing on screens by accident.
 */
@Service
@RequiredArgsConstructor
public class PayrollFieldAccessService {

    /** Item-level figures, which are columns rather than adjustment rows. */
    public static final String FIELD_NET_PAYABLE = "NET_PAYABLE";
    public static final String FIELD_TOTAL_NET_EARNINGS = "TOTAL_NET_EARNINGS";
    public static final String FIELD_HOURLY_RATE = "HOURLY_RATE";

    private final PayrollFieldAccessRepository repository;
    private final PayrollVisibilityPolicy visibilityPolicy;
    private final CurrentUserService currentUserService;

    /** One line's answer for one caller. */
    public record Access(boolean canView, boolean canEdit) {
        static final Access NONE = new Access(false, false);
        static final Access ALL = new Access(true, true);
    }

    /**
     * Everything the current caller may do, by field code.
     *
     * <p>Read once per request rather than per line: a payroll has thirteen of
     * them and asking the database thirteen times to answer one question is how
     * a screen becomes slow for no reason.
     */
    @Transactional(readOnly = true)
    public Map<String, Access> accessForCurrentUser() {
        if (visibilityPolicy.canSeeAmounts()) {
            return Map.of();
        }
        Map<String, Access> byField = new HashMap<>();
        for (String role : visibilityPolicy.currentRoleNames()) {
            for (PayrollFieldAccess row : repository.findForRole(role)) {
                // Somebody with two roles gets the union: the more permissive
                // answer wins, because holding a role cannot take away what
                // another one granted.
                Access existing = byField.get(row.getFieldCode());
                byField.put(row.getFieldCode(), new Access(
                        row.isCanView() || (existing != null && existing.canView()),
                        row.isCanEdit() || (existing != null && existing.canEdit())));
            }
        }
        return byField;
    }

    /** What the caller may do with one line. */
    @Transactional(readOnly = true)
    public Access accessTo(String fieldCode) {
        if (visibilityPolicy.canSeeAmounts()) {
            return Access.ALL;
        }
        return accessForCurrentUser().getOrDefault(fieldCode, Access.NONE);
    }

    /** The whole configuration, for the administration screen. */
    @Transactional(readOnly = true)
    public List<PayrollFieldAccess> findAll() {
        return repository.findAll();
    }

    /**
     * Set one cell of the matrix.
     *
     * <p>Attributed: changing who may see a salary is a decision, not a
     * setting. The database refuses a row for payroll's own roles and refuses
     * edit without view, so those two rules cannot be bypassed from here.
     */
    @Transactional
    public PayrollFieldAccess set(String fieldCode, String roleName, boolean canView, boolean canEdit) {
        PayrollFieldAccess row = repository.findOne(roleName, fieldCode)
                .orElseGet(() -> PayrollFieldAccess.builder()
                        .fieldCode(fieldCode)
                        .roleName(roleName.toLowerCase())
                        .build());
        row.setCanView(canView);
        // Guarded here too, so the caller gets a coherent row rather than a
        // constraint violation they cannot read.
        row.setCanEdit(canEdit && canView);
        row.setUpdatedBy(currentUserService.getCurrentUserId());
        row.setUpdatedAt(OffsetDateTime.now());
        return repository.save(row);
    }
}
