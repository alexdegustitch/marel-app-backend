package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import lombok.Getter;

/**
 * The credentials were correct but the account is not allowed in yet.
 *
 * <p>Distinct from a bad-credentials error on purpose: the frontend needs to show
 * a "waiting for approval" screen rather than "wrong password", and it cannot do
 * that from a generic 400. Carries the status so the client can branch without
 * parsing a message string.
 */
@Getter
public class AccountNotUsableException extends RuntimeException {

    private final UserAccountStatus accountStatus;

    public AccountNotUsableException(UserAccountStatus accountStatus, String message) {
        super(message);
        this.accountStatus = accountStatus;
    }
}
