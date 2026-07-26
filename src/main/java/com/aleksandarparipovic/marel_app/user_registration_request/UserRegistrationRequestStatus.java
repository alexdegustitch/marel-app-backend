package com.aleksandarparipovic.marel_app.user_registration_request;

import java.util.Set;

/**
 * Status of an administrator's review of a self-registered account.
 *
 * <p>Allowed transitions — everything else is refused:
 * <pre>
 *   PENDING -> APPROVED
 *   PENDING -> DECLINED
 *   PENDING -> CANCELLED
 * </pre>
 *
 * <p>There is no reopen path. APPROVED, DECLINED and CANCELLED are terminal, so
 * a decision can never be quietly reversed by re-reviewing an old row; a refused
 * person registers again and gets a new request.
 */
public enum UserRegistrationRequestStatus {

    PENDING,
    APPROVED,
    DECLINED,
    CANCELLED;

    private static final Set<UserRegistrationRequestStatus> FROM_PENDING =
            Set.of(APPROVED, DECLINED, CANCELLED);

    public boolean isOpen() {
        return this == PENDING;
    }

    public boolean canTransitionTo(UserRegistrationRequestStatus target) {
        return this == PENDING && FROM_PENDING.contains(target);
    }
}
