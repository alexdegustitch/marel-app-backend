package com.aleksandarparipovic.marel_app.account.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * What a person may change about themselves.
 *
 * <p>Null means "leave it", as everywhere else in this codebase. Note what is
 * NOT here and cannot be smuggled in: the e-mail address (a credential, with its
 * own exchange), the username (never changes), the role, and the account status.
 */
@Getter
@Setter
public class ProfileUpdateRequest {

    @Size(max = 255, message = "Ime je predugačko.")
    private String firstName;

    @Size(max = 255, message = "Prezime je predugačko.")
    private String lastName;

    @Size(max = 50, message = "Broj telefona je predugačak.")
    private String mobilePhone;

    /**
     * An optional name to be shown by, apart from the legal one.
     *
     * <p>Blank clears it and the first and last name are used again. It never
     * appears on a payroll document — those carry the employee record's legal
     * name, which is a different record for a reason.
     */
    @Size(max = 150, message = "Prikazno ime je predugačko.")
    private String displayName;
}
